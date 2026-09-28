package wiki.twom.plugin.zk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ZkModelTest {

    private fun conn() = ZkConnectionNode(ZkConnectionConfig().apply { name = "test" })

    @Test
    fun `root path is slash`() {
        assertEquals("/", conn().root.path)
    }

    @Test
    fun `child paths are derived and cached`() {
        val c = conn()
        val a = ZkNode("a", c.root, c)
        val b = ZkNode("b", a, c)
        assertEquals("/a", a.path)
        assertEquals("/a/b", b.path)
        assertSame(a.path, a.path) // cached instance
    }

    @Test
    fun `insert keeps children sorted`() {
        val c = conn()
        for (name in listOf("c", "a", "b", "d")) {
            c.root.insertChildSorted(ZkNode(name, c.root, c))
        }
        assertEquals(listOf("a", "b", "c", "d"), c.root.children!!.map { it.name })
    }

    @Test
    fun `child lookup is by name`() {
        val c = conn()
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
        val c = conn()
        c.root.setAllChildren(emptyList())
        assertSame(ZkNode.EMPTY_CHILDREN, c.root.children)
        assertTrue(c.root.loaded)
        c.root.insertChildSorted(ZkNode("n", c.root, c))
        assertEquals(1, c.root.childCount)
    }

    @Test
    fun `setChildren replaces content`() {
        val c = conn()
        c.root.setAllChildren(listOf(ZkNode("k", c.root, c)))
        assertEquals(listOf("k"), c.root.children!!.map { it.name })
    }

    @Test
    fun `connection owner resolves from depth`() {
        val c = conn()
        val deep = ZkNode("a", ZkNode("b", ZkNode("cc", c.root, c), c), c)
        assertSame(c, deep.connection)
    }

    @Test
    fun `diff detects additions and removals`() {
        val diff = diffChildren(listOf("a", "b", "c"), listOf("b", "d"))
        assertEquals(listOf("a", "c"), diff.removed)
        assertEquals(listOf("d"), diff.added)
        assertFalse(diff.isEmpty)
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
}
