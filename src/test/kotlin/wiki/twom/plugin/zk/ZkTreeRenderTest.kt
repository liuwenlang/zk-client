package wiki.twom.plugin.zk

import org.junit.Assert.assertEquals
import org.junit.Test
import javax.swing.JTree

/**
 * Renders a real JTree over [ZkTreeModel] with the exact flags ZkPanel uses
 * (invisible root, large model, fixed row height) and asserts that model
 * mutations turn into visible rows — the layer pure model tests can't see.
 */
class ZkTreeRenderTest {

    private fun newTree(model: ZkTreeModel): JTree = JTree(model).apply {
        isRootVisible = false
        showsRootHandles = true
        rowHeight = 22
        isLargeModel = true
    }

    @Test
    fun `added connection appears as a row`() {
        val model = ZkTreeModel()
        val tree = newTree(model)
        assertEquals(0, tree.rowCount)

        val conn = ZkConnectionNode(ZkConnectionConfig().apply { connectString = "localhost:2181" })
        model.addConnection(conn)
        assertEquals("insert under the invisible root must become a visible row", 1, tree.rowCount)
    }

    @Test
    fun `connection lifecycle keeps the row`() {
        val model = ZkTreeModel()
        val tree = newTree(model)
        val conn = ZkConnectionNode(ZkConnectionConfig().apply { connectString = "localhost:2181" })
        model.addConnection(conn)

        conn.sessionState = ZkSessionState.CONNECTING
        model.connectionStateChanged(conn)
        assertEquals(1, tree.rowCount)

        conn.sessionState = ZkSessionState.CONNECTED
        model.connected(conn) // fresh "/" on connect
        assertEquals("collapsed connection still shows just its own row", 1, tree.rowCount)
        tree.expandPath(model.pathOf(conn))
        assertEquals("expanded connection must show its '/' child row", 2, tree.rowCount)

        model.removeConnection(conn)
        assertEquals(0, tree.rowCount)
    }

    @Test
    fun `several connections added over time all appear`() {
        val model = ZkTreeModel()
        val tree = newTree(model)
        repeat(3) { i ->
            val conn = ZkConnectionNode(ZkConnectionConfig().apply { connectString = "host$i:2181" })
            model.addConnection(conn)
        }
        assertEquals(3, tree.rowCount)
    }
}
