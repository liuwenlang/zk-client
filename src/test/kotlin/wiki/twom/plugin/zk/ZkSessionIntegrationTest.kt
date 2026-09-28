package wiki.twom.plugin.zk

import org.apache.zookeeper.CreateMode
import org.apache.zookeeper.ZooDefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Happy-path test of [ZkSession] against a real embedded ZooKeeper server:
 * connect, read children, disconnect. Complements the failure-path tests in
 * [ZkSessionTest] — the pair guards the state machine from both sides.
 */
class ZkSessionIntegrationTest {

    @Test
    fun `connects and lists children of a live server`() {
        EmbeddedZk().use { zk ->
            val events = ArrayList<ZkSessionEvent>()
            val session = ZkSession(ZkConnectionConfig().apply {
                connectString = "localhost:${zk.port}"
                sessionTimeoutMs = 10_000
            }, { it.run() }) { _, e -> synchronized(events) { events.add(e) } }

            // seed a node so the tree read is non-trivial
            session.connect()
            try {
                assertEquals(ZkSessionState.CONNECTED, session.state)
                assertTrue(session.sessionId != 0L)
                session.run { it.create("/twom", byteArrayOf(1, 2), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT) }
                // "/" also holds the server's own "zookeeper" config node
                assertEquals(listOf("twom", "zookeeper"), session.listChildren("/", false).names)
                assertEquals(byteArrayOf(1, 2).toList(), session.readNode("/twom", false).data?.toList())
            } finally {
                session.disconnect()
            }
            assertEquals(ZkSessionState.DISCONNECTED, session.state)
            assertTrue(
                "expected Connected then Disconnected, got $events",
                synchronized(events) {
                    events.any { it is ZkSessionEvent.Connected } && events.any { it is ZkSessionEvent.Disconnected }
                },
            )
        }
    }
}
