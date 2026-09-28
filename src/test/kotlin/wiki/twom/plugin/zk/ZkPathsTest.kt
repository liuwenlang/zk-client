package wiki.twom.plugin.zk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZkPathsTest {

    @Test
    fun `child path builds correctly`() {
        assertEquals("/a", ZkPaths.childPath("/", "a"))
        assertEquals("/a/b", ZkPaths.childPath("/a", "b"))
    }

    @Test
    fun `parent of root is null`() {
        assertNull(ZkPaths.parentOf("/"))
    }

    @Test
    fun `parent of top level is root`() {
        assertEquals("/", ZkPaths.parentOf("/a"))
        assertEquals("/a", ZkPaths.parentOf("/a/b"))
    }

    @Test
    fun `name of path`() {
        assertEquals("/", ZkPaths.nameOf("/"))
        assertEquals("a", ZkPaths.nameOf("/a"))
        assertEquals("b", ZkPaths.nameOf("/a/b"))
    }

    @Test
    fun `segments split`() {
        assertEquals(listOf("a", "b"), ZkPaths.segments("/a/b"))
        assertEquals(emptyList<String>(), ZkPaths.segments("/"))
    }

    @Test
    fun `validate accepts good paths`() {
        assertNull(ZkPaths.validate("/"))
        assertNull(ZkPaths.validate("/a/b/c"))
        assertNull(ZkPaths.validate("/a-1_b.c"))
    }

    @Test
    fun `validate rejects bad paths`() {
        assertTrue(ZkPaths.validate("a/b") != null)
        assertTrue(ZkPaths.validate("/a//b") != null)
        assertTrue(ZkPaths.validate("/a/") != null)
        assertTrue(ZkPaths.validate("/a b") != null)
    }
}
