package wiki.twom.plugin.zk

import org.apache.zookeeper.KeeperException
import org.apache.zookeeper.WatchedEvent
import org.apache.zookeeper.Watcher
import org.apache.zookeeper.ZooKeeper
import org.apache.zookeeper.data.Stat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

enum class ZkSessionState { DISCONNECTED, CONNECTING, CONNECTED, EXPIRED, AUTH_FAILED }

sealed interface ZkSessionEvent {
    data class Connected(val sessionId: Long, val sessionTimeoutMs: Int) : ZkSessionEvent
    data object Disconnected : ZkSessionEvent
    data object ConnectTimeout : ZkSessionEvent
    data object Expired : ZkSessionEvent
    data object AuthFailed : ZkSessionEvent
    data class NodeChanged(val path: String, val type: Watcher.Event.EventType) : ZkSessionEvent
}

/**
 * Wraps a [ZooKeeper] client. All methods must be called off the EDT (they block); events are fired on the client thread.
 *
 * Failure semantics (ZooKeeper 3.9 client behavior): a client that cannot reach a server
 * within the session timeout does NOT retry in silence — it reports `Expired`, after which
 * the handle is dead. So:
 *  - an attempt that never connected turns `Expired` into [ZkSessionEvent.ConnectTimeout]
 *    (state DISCONNECTED) instead of looping re-connects against a dead host;
 *  - only a session that HAD connected auto-reconnects once ([hadSession]); if that
 *    reconnect attempt also expires, it too is reported as a connection failure.
 */
class ZkSession(
    val config: ZkConnectionConfig,
    private val executor: Executor,
    private val onEvent: (ZkSession, ZkSessionEvent) -> Unit,
) : Watcher {

    private val clientRef = AtomicReference<ZooKeeper?>()

    /** Non-null while an initial [connect] is awaiting its outcome; read on the ZK event thread. */
    @Volatile
    private var connectLatch: CountDownLatch? = null

    /** True once the current client reached SyncConnected; see class docs. */
    @Volatile
    private var hadSession = false

    @Volatile
    var state: ZkSessionState = ZkSessionState.DISCONNECTED
        private set

    @Volatile
    var sessionId: Long = 0
        private set

    val isConnected: Boolean
        get() = state == ZkSessionState.CONNECTED

    @Synchronized
    fun connect() {
        if (state == ZkSessionState.CONNECTING || state == ZkSessionState.CONNECTED) return
        closeClient()
        hadSession = false
        state = ZkSessionState.CONNECTING
        val latch = CountDownLatch(1)
        connectLatch = latch
        val client = try {
            ZooKeeper(config.connectString, config.sessionTimeoutMs, this)
        } catch (t: Throwable) {
            state = ZkSessionState.DISCONNECTED // else the CONNECTING guard would swallow all retries
            connectLatch = null
            throw t
        }
        clientRef.set(client)
        if (config.hasAuth) {
            client.addAuthInfo("digest", "${config.authUser}:${config.authPassword}".toByteArray())
        }
        val awaitMs = config.sessionTimeoutMs.coerceAtLeast(10_000).toLong() + 5_000
        try {
            latch.await(awaitMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        connectLatch = null
        // The latch is also released by Expired/Disconnected/Closed/AuthFailed — only
        // CONNECTED means success. CONNECTING here means nothing arrived at all within the
        // budget; every other state was already reported by its own watcher branch.
        if (state == ZkSessionState.CONNECTING) {
            state = ZkSessionState.DISCONNECTED // set before close(): the Closed watcher event then stays silent
            closeClient() // stop the client's endless background retries
            onEvent(this, ZkSessionEvent.ConnectTimeout)
        }
    }

    @Synchronized
    fun disconnect() {
        val wasLive = state != ZkSessionState.DISCONNECTED
        state = ZkSessionState.DISCONNECTED // before close(): the Closed watcher event then stays silent
        closeClient()
        if (wasLive) onEvent(this, ZkSessionEvent.Disconnected)
    }

    private fun closeClient() {
        try {
            clientRef.getAndSet(null)?.close()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
        }
    }

    override fun process(event: WatchedEvent) {
        when (event.state) {
            Watcher.Event.KeeperState.SyncConnected -> {
                // A close can race the connect: ignore events from a client we already dropped.
                val client = clientRef.get() ?: return
                hadSession = true
                state = ZkSessionState.CONNECTED
                sessionId = client.sessionId
                connectLatch?.countDown()
                onEvent(this, ZkSessionEvent.Connected(sessionId, client.sessionTimeout))
            }

            Watcher.Event.KeeperState.Disconnected -> {
                val latch = connectLatch
                if (latch != null) {
                    // Initial connect attempt lost its transport before SyncConnected ever
                    // arrived — wake connect(); it reports the failure. A "Disconnected"
                    // balloon for a connection that never existed would only mislead.
                    latch.countDown()
                } else if (state != ZkSessionState.EXPIRED && state != ZkSessionState.DISCONNECTED) {
                    state = ZkSessionState.CONNECTING
                    onEvent(this, ZkSessionEvent.Disconnected)
                }
            }

            Watcher.Event.KeeperState.Expired -> {
                // The client handle is dead after expiry. State is written BEFORE
                // countDown() so connect() never wakes to a stale CONNECTING and fires
                // a duplicate failure on top of the one dispatched here.
                if (clientRef.get() == null) return // stale client we already tore down
                if (hadSession) {
                    state = ZkSessionState.EXPIRED
                    onEvent(this, ZkSessionEvent.Expired)
                    connectLatch?.countDown()
                    if (config.autoReconnect) {
                        closeClient()
                        executor.execute {
                            // disconnect() (state -> DISCONNECTED) cancels the pending reconnect
                            if (state == ZkSessionState.EXPIRED) {
                                try {
                                    connect()
                                } catch (_: Exception) {
                                }
                            }
                        }
                    }
                } else {
                    // Nothing ever connected through this client: this is a plain
                    // connection failure, not a session loss — do not loop reconnects
                    // against an unreachable server.
                    state = ZkSessionState.DISCONNECTED
                    connectLatch?.countDown()
                    onEvent(this, ZkSessionEvent.ConnectTimeout)
                    closeClient()
                }
            }

            Watcher.Event.KeeperState.AuthFailed -> {
                state = ZkSessionState.AUTH_FAILED
                connectLatch?.countDown()
                onEvent(this, ZkSessionEvent.AuthFailed)
            }

            Watcher.Event.KeeperState.Closed -> {
                connectLatch?.countDown()
                // Closed only ever follows our own close() (connect/disconnect teardown or the
                // Expired branch); it must not clobber the state those paths set up.
                if (state != ZkSessionState.DISCONNECTED && state != ZkSessionState.EXPIRED) {
                    state = ZkSessionState.DISCONNECTED
                    onEvent(this, ZkSessionEvent.Disconnected)
                }
            }

            else -> {}
        }
        if (event.type != Watcher.Event.EventType.None) {
            onEvent(this, ZkSessionEvent.NodeChanged(event.path, event.type))
        }
    }

    fun <T> run(op: (ZooKeeper) -> T): T {
        val client = clientRef.get()
        if (client == null || !isConnected) {
            throw IllegalStateException("Not connected (state: $state)")
        }
        return try {
            op(client)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: Throwable) {
            if (e is KeeperException.SessionExpiredException) state = ZkSessionState.EXPIRED
            throw e
        }
    }

    fun readNode(path: String, watch: Boolean): NodeData = run { client ->
        val stat = Stat()
        val data = client.getData(path, watch, stat)
        NodeData(data, stat)
    }

    fun listChildren(path: String, watch: Boolean): ChildrenData = run { client ->
        val stat = Stat()
        val children = client.getChildren(path, watch, stat)
        ChildrenData(children.sorted(), stat)
    }

    fun exists(path: String, watch: Boolean): Stat? = run { client ->
        client.exists(path, watch)
    }

    data class NodeData(val data: ByteArray?, val stat: Stat)

    data class ChildrenData(val names: List<String>, val stat: Stat)
}
