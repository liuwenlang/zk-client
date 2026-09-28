package wiki.twom.plugin.zk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Failure-path tests for [ZkSession.connect] against servers that are not healthy
 * ZooKeepers. The invariant under test: a failed attempt always ends DISCONNECTED
 * with a ConnectTimeout/AuthFailed event, never silently stuck CONNECTING — a stuck
 * session swallows every later connect() call (the CONNECTING guard returns early)
 * and the connection becomes unretryable.
 *
 * The ZooKeeper 3.9 client reports a connect that cannot be established within the
 * session timeout as `Expired`; the failure event is dispatched on the client's
 * event thread, so it can land microseconds AFTER connect() returns — the helpers
 * below poll for it instead of asserting instantly.
 */
class ZkSessionTest {

    private fun newConfig(connectString: String): ZkConnectionConfig =
        ZkConnectionConfig().apply {
            this.connectString = connectString
            sessionTimeoutMs = 1_000 // client gives up (Expired) after ~1-2s
        }

    /** Accepts TCP connections and closes them immediately — a server that drops us. */
    private class DroppingServer : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())

        init {
            thread(isDaemon = true, name = "dropping-server") {
                while (!server.isClosed) {
                    try {
                        server.accept().close()
                    } catch (_: IOException) {
                        break
                    }
                }
            }
        }

        val port: Int get() = server.localPort

        override fun close() {
            server.close()
        }
    }

    /** Reserves an ephemeral port and frees it — connecting there is refused. */
    private fun refusedPort(): Int =
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    /** Waits up to [timeoutMs] for the asynchronous watcher-side events to land. */
    private fun awaitTimeouts(events: MutableList<ZkSessionEvent>, timeoutMs: Long = 5_000): Int {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val count = synchronized(events) { events.count { it is ZkSessionEvent.ConnectTimeout } }
            if (count > 0) return count
            Thread.sleep(20)
        }
        return synchronized(events) { events.count { it is ZkSessionEvent.ConnectTimeout } }
    }

    @Test
    fun `dropped connection fails instead of sticking in CONNECTING`() {
        DroppingServer().use { server ->
            val events = ArrayList<ZkSessionEvent>()
            val session = ZkSession(newConfig("localhost:${server.port}"), { it.run() }) { _, e ->
                synchronized(events) { events.add(e) }
            }
            session.connect()
            assertNotEquals("a dropped initial connect must not report CONNECTED", ZkSessionState.CONNECTED, session.state)
            assertNotEquals(
                "a dropped initial connect must not stay CONNECTING — the guard in connect() would then swallow all retries",
                ZkSessionState.CONNECTING,
                session.state,
            )
            assertTrue(
                "the user must be told the attempt failed, got $events",
                awaitTimeouts(events) > 0,
            )
        }
    }

    @Test
    fun `failed session can be retried`() {
        DroppingServer().use { server ->
            val events = ArrayList<ZkSessionEvent>()
            val session = ZkSession(newConfig("localhost:${server.port}"), { it.run() }) { _, e ->
                synchronized(events) { events.add(e) }
            }
            session.connect()
            session.connect() // must be a real second attempt, not a no-op
            assertEquals("both attempts must report their failure", 2, awaitTimeouts(events))
        }
    }

    @Test
    fun `refused connection times out to DISCONNECTED`() {
        val events = ArrayList<ZkSessionEvent>()
        val session = ZkSession(newConfig("localhost:${refusedPort()}"), { it.run() }) { _, e ->
            synchronized(events) { events.add(e) }
        }
        session.connect()
        assertEquals(ZkSessionState.DISCONNECTED, session.state)
        assertTrue(
            "the user must be told the attempt timed out, got $events",
            awaitTimeouts(events) > 0,
        )
    }

    @Test
    fun `invalid connect string fails fast without sticking in CONNECTING`() {
        // "//bad" is an invalid chroot: ConnectStringParser rejects it in the constructor
        val events = ArrayList<ZkSessionEvent>()
        val session = ZkSession(newConfig("localhost:2181//bad"), { it.run() }) { _, e ->
            synchronized(events) { events.add(e) }
        }
        try {
            session.connect()
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(
            "a throwing constructor must reset the state so retries are possible",
            ZkSessionState.DISCONNECTED,
            session.state,
        )
    }
}
