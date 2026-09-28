package wiki.twom.plugin.zk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ZkConnectionTest {

    @Test
    fun `valid connect strings pass`() {
        assertNull(connectStringHostIssue("localhost:2181"))
        assertNull(connectStringHostIssue("192.168.9.99:2181"))
        assertNull(connectStringHostIssue("zk1:2181,zk2:2181/prod"))
        assertNull(connectStringHostIssue("zk-1.example.com:2181"))
    }

    @Test
    fun `near-IP host is rejected`() {
        // fake-IP DNS would "resolve" this to a proxy address and fail opaquely later
        assertEquals(
            "Host '192.168.9.99t' looks like a mistyped IP address",
            connectStringHostIssue("192.168.9.99t:2181"),
        )
    }

    @Test
    fun `near-IP host is caught in multi-host lists and before chroot`() {
        assertEquals(
            "Host '10.0.0.1x' looks like a mistyped IP address",
            connectStringHostIssue("10.0.0.1x:2181,192.168.9.99:2181"),
        )
        assertEquals(
            "Host '192.168.9.99x' looks like a mistyped IP address",
            connectStringHostIssue("192.168.9.99x:2181/chroot"),
        )
    }
}
