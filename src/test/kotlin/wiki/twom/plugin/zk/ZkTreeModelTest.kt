package wiki.twom.plugin.zk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.swing.tree.TreePath

/**
 * Pure model tests. No JTree: a headless JUnit run has no real Look & Feel, so JTree's own row
 * counting cannot be asserted here — and a failing `DefaultTreeModel` control test proved the
 * environment, not the model, was at fault. Path validity is checked against the model's own
 * getChild/getIndexOfChild instead, which is what actually matters.
 */
class ZkTreeModelTest {

    private fun conn(name: String) = ZkConnectionNode(ZkConnectionConfig().apply { this.name = name })

    private fun assertPath(expected: List<Any>, actual: TreePath) {
        assertEquals(expected.size, actual.pathCount)
        expected.forEachIndexed { i, component -> assertSame(component, actual.getPathComponent(i)) }
    }

    /** Walks [path] verifying each component is really a child of its predecessor. */
    private fun assertPathResolvable(model: ZkTreeModel, path: TreePath) {
        var parent: Any = model.getRoot()
        for (i in 1 until path.pathCount) {
            val child = path.getPathComponent(i)
            val index = model.getIndexOfChild(parent, child)
            assertTrue("index of $child under $parent", index >= 0)
            assertSame(child, model.getChild(parent, index))
            parent = child
        }
    }

    @Test
    fun `root path is slash`() {
        assertEquals("/", conn("c").root.path)
    }

    @Test
    fun `child paths are derived and cached`() {
        val c = conn("c")
        val a = ZkNode("a", c.root, c)
        val b = ZkNode("b", a, c)
        assertEquals("/a", a.path)
        assertEquals("/a/b", b.path)
        assertSame(a.path, a.path) // cached instance
    }

    @Test
    fun `insert keeps children sorted`() {
        val c = conn("c")
        for (name in listOf("c", "a", "b", "d")) {
            c.root.insertChildSorted(ZkNode(name, c.root, c))
        }
        assertEquals(listOf("a", "b", "c", "d"), c.root.children!!.map { it.name })
    }

    @Test
    fun `child lookup is by name`() {
        val c = conn("c")
        c.root.insertChildSorted(ZkNode("x", c.root, c))
        c.root.insertChildSorted(ZkNode("y", c.root, c))
        assertEquals("/x", c.root.childNamed("x")?.path)
        assertNull(c.root.childNamed("z"))
        assertEquals(0, c.root.indexOfChildNamed("x"))
        assertEquals(1, c.root.indexOfChildNamed("y"))
        assertEquals(-1, c.root.indexOfChildNamed("z"))
    }

    @Test
    fun `childless nodes share the empty list until mutated`() {
        val c = conn("c")
        c.root.setAllChildren(emptyList())
        assertSame(ZkNode.EMPTY_CHILDREN, c.root.children)
        assertTrue(c.root.loaded)
        c.root.insertChildSorted(ZkNode("n", c.root, c))
        assertEquals(1, c.root.childCount)
    }

    @Test
    fun `setChildren replaces content`() {
        val c = conn("c")
        c.root.setAllChildren(listOf(ZkNode("k", c.root, c)))
        assertEquals(listOf("k"), c.root.children!!.map { it.name })
    }

    @Test
    fun `connection owner resolves from depth`() {
        val c = conn("c")
        val deep = ZkNode("a", ZkNode("b", ZkNode("cc", c.root, c), c), c)
        assertSame(c, deep.connection)
    }

    @Test
    fun `diff detects additions and removals`() {
        val diff = diffChildren(listOf("a", "b", "c"), listOf("b", "d"))
        assertEquals(listOf("a", "c"), diff.removed)
        assertEquals(listOf("d"), diff.added)
        assertTrue(!diff.isEmpty)
    }

    @Test
    fun `diff of identical lists is empty`() {
        assertTrue(diffChildren(listOf("a", "b"), listOf("a", "b")).isEmpty)
        assertTrue(diffChildren(emptyList(), emptyList()).isEmpty)
    }

    @Test
    fun `acl permissions render as cdrwa`() {
        assertEquals("all (cdrwa)", ZkDetailsPanel.aclPerms(org.apache.zookeeper.ZooDefs.Perms.ALL))
        assertEquals("r", ZkDetailsPanel.aclPerms(org.apache.zookeeper.ZooDefs.Perms.READ))
        assertEquals("cr", ZkDetailsPanel.aclPerms(
            org.apache.zookeeper.ZooDefs.Perms.CREATE or org.apache.zookeeper.ZooDefs.Perms.READ,
        ))
        assertEquals("cd", ZkDetailsPanel.aclPerms(
            org.apache.zookeeper.ZooDefs.Perms.CREATE or org.apache.zookeeper.ZooDefs.Perms.DELETE,
        ))
        assertEquals("none", ZkDetailsPanel.aclPerms(0))
    }

    // ------------------------------------------------------------- paths (the regression)

    @Test
    fun `pathOf root is the tree root only`() {
        val model = ZkTreeModel()
        assertPath(listOf(model.getRoot()), model.pathOf(model.getRoot()))
    }

    @Test
    fun `pathOf connection sits directly under the tree root`() {
        val model = ZkTreeModel()
        val c = conn("c")
        model.addConnection(c)
        assertPath(listOf(model.getRoot(), c), model.pathOf(c))
    }

    @Test
    fun `pathOf root znode includes the connection`() {
        val model = ZkTreeModel()
        val c = conn("c")
        c.sessionState = ZkSessionState.CONNECTED
        model.addConnection(c)

        // the regression: the connection used to be skipped, yielding [treeRoot, root]
        assertPath(listOf(model.getRoot(), c, c.root), model.pathOf(c.root))
    }

    @Test
    fun `pathOf nested znode includes the connection and every ancestor`() {
        val model = ZkTreeModel()
        val c = conn("c")
        c.sessionState = ZkSessionState.CONNECTED
        model.addConnection(c)
        model.applyChildren(c, c.root, listOf("a", "b"), null)
        val a = model.getChild(c.root, 0) as ZkNode
        model.applyChildren(c, a, listOf("x"), null)
        val x = model.getChild(a, 0) as ZkNode

        assertPath(listOf(model.getRoot(), c, c.root, a, x), model.pathOf(x))
    }

    @Test
    fun `every produced path resolves through getChild`() {
        val model = ZkTreeModel()
        val c = conn("c")
        c.sessionState = ZkSessionState.CONNECTED
        model.addConnection(c)
        model.applyChildren(c, c.root, listOf("a", "b"), null)
        val a = model.getChild(c.root, 0) as ZkNode
        model.applyChildren(c, a, listOf("x", "y"), null)

        for (node in listOf<Any>(model.getRoot(), c, c.root, a,
            model.getChild(a, 0), model.getChild(a, 1))) {
            assertPathResolvable(model, model.pathOf(node))
        }
    }

    @Test
    fun `pathOf is stable across the loading placeholder`() {
        val model = ZkTreeModel()
        val c = conn("c")
        c.sessionState = ZkSessionState.CONNECTED
        model.addConnection(c)
        model.applyChildren(c, c.root, listOf("a"), null)
        val a = model.getChild(c.root, 0) as ZkNode

        a.loading = true
        model.notifyLoading(a)
        assertPath(listOf(model.getRoot(), c, c.root, a), model.pathOf(a))

        model.applyChildren(c, a, listOf("x"), null)
        assertPath(listOf(model.getRoot(), c, c.root, a), model.pathOf(a))
    }

    // ------------------------------------------------------------- tree model contract

    @Test
    fun `adding a connection exposes it as a child of the root`() {
        val model = ZkTreeModel()
        val c = conn("c")
        model.addConnection(c)

        assertEquals(1, model.getChildCount(model.getRoot()))
        assertSame(c, model.getChild(model.getRoot(), 0))
        assertSame(c, model.findConnection(c.config.id))
    }

    @Test
    fun `connection exposes its root znode only when connected`() {
        val model = ZkTreeModel()
        val c = conn("c")
        model.addConnection(c)

        assertEquals(0, model.getChildCount(c))
        c.sessionState = ZkSessionState.CONNECTED
        model.connected(c)
        assertEquals(1, model.getChildCount(c))
        assertSame(c.root, model.getChild(c, 0))
    }

    @Test
    fun `applyChildren keeps the order it is given`() {
        val model = ZkTreeModel()
        val c = conn("c")
        c.sessionState = ZkSessionState.CONNECTED
        model.addConnection(c)
        // callers are responsible for sorting: ZkSession.listChildren returns names sorted
        model.applyChildren(c, c.root, listOf("a", "b"), null)

        assertEquals(2, model.getChildCount(c.root))
        assertEquals("/a", (model.getChild(c.root, 0) as ZkNode).path)
        assertEquals("/b", (model.getChild(c.root, 1) as ZkNode).path)
    }

    @Test
    fun `loading a node reveals a placeholder row then the children`() {
        val model = ZkTreeModel()
        val c = conn("c")
        c.sessionState = ZkSessionState.CONNECTED
        model.addConnection(c)
        model.applyChildren(c, c.root, listOf("a"), null)

        val a = model.getChild(c.root, 0) as ZkNode
        a.loading = true
        model.notifyLoading(a)
        assertEquals(1, model.getChildCount(a))
        assertSame(LoadingPlaceholder, model.getChild(a, 0))

        model.applyChildren(c, a, listOf("x", "y"), null)
        assertEquals(2, model.getChildCount(a))
        assertEquals("/a/x", (model.getChild(a, 0) as ZkNode).path)
        assertEquals("/a/y", (model.getChild(a, 1) as ZkNode).path)
    }

    @Test
    fun `diff keeps loaded subtrees`() {
        val model = ZkTreeModel()
        val c = conn("c")
        model.addConnection(c)
        model.applyChildren(c, c.root, listOf("a", "b"), null)

        val before = model.getChild(c.root, 1) as ZkNode
        model.applyChildrenDiff(c, c.root, listOf("b", "c"), null)

        assertEquals(2, model.getChildCount(c.root))
        assertSame(before, model.getChild(c.root, 0) as ZkNode) // "b" subtree preserved
        assertEquals("/c", (model.getChild(c.root, 1) as ZkNode).path)
    }

    @Test
    fun `removeNode drops the child`() {
        val model = ZkTreeModel()
        val c = conn("c")
        c.sessionState = ZkSessionState.CONNECTED
        model.addConnection(c)
        model.applyChildren(c, c.root, listOf("a"), null)
        val a = model.getChild(c.root, 0) as ZkNode

        model.removeNode(a)

        assertEquals(0, model.getChildCount(c.root))
        assertEquals(-1, model.getIndexOfChild(c.root, a))
    }

    @Test
    fun `removeConnection drops the row`() {
        val model = ZkTreeModel()
        val c = conn("c")
        model.addConnection(c)

        model.removeConnection(c)

        assertEquals(0, model.getChildCount(model.getRoot()))
        assertNull(model.findConnection(c.config.id))
    }

    @Test
    fun `replaceConnection keeps its position`() {
        val model = ZkTreeModel()
        val first = conn("first")
        val second = conn("second")
        model.addConnection(first)
        model.addConnection(second)

        val replacement = ZkConnectionNode(second.config)
        model.replaceConnection(second, replacement)

        assertEquals(2, model.getChildCount(model.getRoot()))
        assertSame(first, model.getChild(model.getRoot(), 0))
        assertSame(replacement, model.getChild(model.getRoot(), 1))
    }
}
