package wiki.twom.plugin.zk

import org.apache.zookeeper.server.NIOServerCnxnFactory
import org.apache.zookeeper.server.ZooKeeperServer
import java.net.InetAddress
import java.net.InetSocketAddress

/** Real single-node ZooKeeper on an ephemeral loopback port, for tests. */
class EmbeddedZk : AutoCloseable {
    private val factory = NIOServerCnxnFactory()
    private val server = ZooKeeperServer(
        java.nio.file.Files.createTempDirectory("zk-data").toFile(),
        java.nio.file.Files.createTempDirectory("zk-logs").toFile(),
        2000,
    )

    init {
        factory.configure(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        factory.startup(server)
    }

    val port: Int get() = factory.localPort

    override fun close() {
        factory.shutdown()
    }
}
