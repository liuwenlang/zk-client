package wiki.twom.plugin.zk

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import org.apache.zookeeper.data.Stat
import javax.swing.event.EventListenerList
import javax.swing.event.TreeModelEvent
import javax.swing.event.TreeModelListener
import javax.swing.tree.TreeModel
import javax.swing.tree.TreePath

object LoadingPlaceholder {
    override fun toString(): String = "Loading…" // fallback for non-UI contexts
}

/**
 * Swing [TreeModel] over the pure [ZkNode] data tree. The JTree pulls children lazily
 * through getChild/getChildCount, so only visible rows are ever touched.
 *
 * Mutations run on the EDT and fire fine-grained events:
 *  - [applyChildrenStreamed] feeds a huge child list to the UI in batches (render while loading)
 *  - [applyChildrenDiff] patches changed names with precise insert/remove events,
 *    preserving loaded subtrees and expansion state
 *  - [unloadDescendants]/[resetNode] implement cache eviction for collapsed areas
 */
class ZkTreeModel : TreeModel {

    private val listeners = EventListenerList()
    private val connections = ArrayList<ZkConnectionNode>()
    private val treeRoot = Any()

    companion object {
        /** Child lists larger than this are streamed to the UI in batches of this size. */
        val STREAM_BATCH = ZkConstants.STREAM_BATCH_SIZE
    }

    // ------------------------------------------------------------- TreeModel (pull-based)

    override fun getRoot(): Any = treeRoot

    override fun getChildCount(parent: Any): Int = when (parent) {
        treeRoot -> connections.size
        is ZkConnectionNode -> if (parent.sessionState == ZkSessionState.CONNECTED) 1 else 0
        is ZkNode -> when {
            parent.loaded -> parent.children!!.size
            parent.loading -> 1 // "Loading…" placeholder row
            else -> 0
        }
        else -> 0
    }

    override fun getChild(parent: Any, index: Int): Any = when (parent) {
        treeRoot -> connections[index]
        is ZkConnectionNode -> parent.root
        is ZkNode -> if (parent.loaded) parent.children!![index] else LoadingPlaceholder
        else -> LoadingPlaceholder
    }

    override fun isLeaf(node: Any): Boolean = when (node) {
        is ZkConnectionNode -> false
        is ZkNode -> {
            // If we know it's a leaf, report it as such
            if (node.hasChildren == false) return true
            // If loaded and empty, it's a leaf
            if (node.loaded && node.children!!.isEmpty()) return true
            // Otherwise assume it might have children (show expand button)
            false
        }
        // The invisible treeRoot: reporting it as a leaf makes JTree treat it as forever
        // childless — connections inserted under it never render as rows.
        else -> false
    }

    override fun getIndexOfChild(parent: Any?, child: Any?): Int = when {
        parent === treeRoot && child is ZkConnectionNode -> connections.indexOf(child)
        parent is ZkConnectionNode -> if (child === parent.root) 0 else -1
        parent is ZkNode && child is ZkNode -> parent.indexOfChildNamed(child.name)
        else -> -1
    }

    override fun valueForPathChanged(path: TreePath?, newValue: Any?) {}

    override fun addTreeModelListener(l: TreeModelListener) {
        listeners.add(TreeModelListener::class.java, l)
    }

    override fun removeTreeModelListener(l: TreeModelListener) {
        listeners.remove(TreeModelListener::class.java, l)
    }

    // ------------------------------------------------------------- inspection

    fun connectionNodes(): List<ZkConnectionNode> = connections

    fun findConnection(id: String): ZkConnectionNode? =
        connections.firstOrNull { it.config.id == id }

    fun pathOf(node: Any): TreePath {
        val parts = ArrayList<Any>(10)
        var current: Any? = node
        while (current is ZkNode) {
            parts.add(current)
            current = current.parent
        }
        if (current is ZkConnectionNode) {
            parts.add(current)
        } else {
            // A znode's parent chain stops at the "/" znode (its parent is null), so the
            // owning connection has to be resolved separately — otherwise the path skips it
            // and JTree cannot locate the row.
            (node as? ZkNode)?.connection?.let { parts.add(it) }
        }
        parts.add(treeRoot)
        parts.reverse()
        return TreePath(parts.toTypedArray())
    }

    // ------------------------------------------------------------- mutations (EDT only)

    fun addConnection(conn: ZkConnectionNode) {
        connections.add(conn)
        fireNodesInserted(treeRoot, intArrayOf(connections.size - 1), arrayOf(conn))
    }

    fun removeConnection(conn: ZkConnectionNode) {
        conn.root.cancelChunk()
        val idx = connections.indexOf(conn)
        if (idx < 0) return
        connections.removeAt(idx)
        fireNodesRemoved(treeRoot, intArrayOf(idx), arrayOf(conn))
    }

    fun replaceConnection(old: ZkConnectionNode, new: ZkConnectionNode) {
        old.root.cancelChunk()
        val idx = connections.indexOf(old)
        if (idx < 0) {
            addConnection(new)
            return
        }
        connections[idx] = new
        fireNodesRemoved(treeRoot, intArrayOf(idx), arrayOf(old))
        fireNodesInserted(treeRoot, intArrayOf(idx), arrayOf(new))
    }

    fun connectionStateChanged(conn: ZkConnectionNode) {
        fireNodesChanged(conn)
        fireStructureChanged(conn)
    }

    /** Fresh "/" on every (re)connect; drops the previous cached tree. */
    fun connected(conn: ZkConnectionNode) {
        conn.root.cancelChunk()
        conn.root = ZkNode("", null, conn)
        conn.loadedCount = 0
        fireStructureChanged(conn)
    }

    /** Reveal/hide the loading placeholder row. */
    fun notifyLoading(node: ZkNode) = fireStructureChanged(node)

    fun nodeChanged(node: ZkNode) = fireNodesChanged(node)

    /** Applies a full, sorted child list with a single structure event. */
    fun applyChildren(conn: ZkConnectionNode, node: ZkNode, sortedNames: List<String>, stat: Stat?) {
        node.cancelChunk()
        val wasLoaded = node.loaded
        node.setAllChildren(sortedNames.map { ZkNode(it, node, conn) })
        if (!wasLoaded) conn.loadedCount++
        node.stat = stat
        node.loading = false
        fireStructureChanged(node)
    }

    /**
     * Streams a large sorted child list into the UI in [STREAM_BATCH] batches scheduled on
     * the EDT, so the tree keeps painting between batches ("render while loading").
     * The data list becomes visible immediately and grows batch by batch.
     */
    fun applyChildrenStreamed(conn: ZkConnectionNode, node: ZkNode, sortedNames: List<String>, stat: Stat?) {
        if (sortedNames.size <= STREAM_BATCH) {
            applyChildren(conn, node, sortedNames, stat)
            return
        }
        node.cancelChunk()
        val wasLoaded = node.loaded
        node.children = ArrayList(STREAM_BATCH)
        if (!wasLoaded) conn.loadedCount++
        node.stat = stat
        val state = ChunkState()
        node.chunkState = state
        val application = ApplicationManager.getApplication()
        var from = 0

        fun schedule() {
            application.invokeLater({
                if (state.cancelled) return@invokeLater
                val list = node.children as? ArrayList ?: return@invokeLater
                val to = minOf(from + STREAM_BATCH, sortedNames.size)
                val indices = IntArray(to - from)
                val added = arrayOfNulls<Any?>(to - from)
                for (i in from until to) {
                    val child = ZkNode(sortedNames[i], node, conn)
                    list.add(child)
                    indices[i - from] = list.size - 1
                    added[i - from] = child
                }
                fireNodesInserted(node, indices, added)
                from = to
                if (from < sortedNames.size) {
                    schedule()
                } else {
                    node.chunkState = null
                    node.loading = false
                }
            }, ModalityState.any()) // keep streaming while a modal dialog is open
        }
        schedule()
    }

    /**
     * Precise diff update: removes/inserts only the changed names and never rebuilds
     * existing children, so loaded subtrees and expansion state survive watch refreshes.
     */
    fun applyChildrenDiff(conn: ZkConnectionNode, node: ZkNode, sortedNames: List<String>, stat: Stat?) {
        if (node.chunkState != null || !node.loaded) {
            // a stream is mid-flight or nothing cached yet — full replace is cheaper
            applyChildren(conn, node, sortedNames, stat)
            return
        }
        node.stat = stat
        val current = node.children!!
        val diff = diffChildren(current.map { it.name }, sortedNames)
        if (diff.isEmpty) return

        // removals: one event carrying the ORIGINAL indices (collected before any removal)
        val removed = ArrayList<Pair<Int, ZkNode>>()
        for (name in diff.removed) {
            val idx = node.indexOfChildNamed(name)
            if (idx >= 0) removed.add(idx to current[idx])
        }
        if (removed.isNotEmpty()) {
            val indices = IntArray(removed.size)
            val nodes = arrayOfNulls<Any?>(removed.size)
            removed.forEachIndexed { i, (idx, child) ->
                indices[i] = idx
                nodes[i] = child
                current.remove(child)
            }
            fireNodesRemoved(node, indices, nodes)
        }

        // insertions: one event carrying the FINAL positions
        val added = diff.added.map { name -> ZkNode(name, node, conn).also { node.insertChildSorted(it) } }
        if (added.isNotEmpty()) {
            val live = node.children!!
            val indices = IntArray(added.size)
            val nodes = arrayOfNulls<Any?>(added.size)
            added.forEachIndexed { i, child ->
                indices[i] = live.indexOf(child)
                nodes[i] = child
            }
            fireNodesInserted(node, indices, nodes)
        }
    }

    /** Removes [node] from its parent (delete / NodeDeleted watch); adjusts the cache count. */
    fun removeNode(node: ZkNode) {
        val parent = node.parent ?: return
        val conn = node.connection
        val list = parent.children ?: return
        val idx = list.indexOf(node)
        if (idx < 0) return
        node.cancelChunk()
        if (conn != null) {
            if (node.loaded) conn.loadedCount--
            unloadDescendants(conn, node)
        }
        list.removeAt(idx)
        fireNodesRemoved(parent, intArrayOf(idx), arrayOf<Any?>(node))
    }

    /**
     * Drops loaded child lists below [node]'s own children (eviction) and returns how many
     * lists were freed. Names stay cached, so re-expanding reloads one level at a time.
     */
    fun unloadDescendants(conn: ZkConnectionNode, node: ZkNode): Int {
        var freed = 0
        val kids = node.children ?: return 0
        for (child in kids) {
            freed += unloadDescendants(conn, child)
            if (child.loaded) {
                child.cancelChunk()
                child.children = null
                child.loading = false
                conn.loadedCount--
                freed++
            }
        }
        return freed
    }

    /** Clears a node's cached list (explicit refresh) so the next load re-fetches it. */
    fun resetNode(conn: ZkConnectionNode, node: ZkNode) {
        node.cancelChunk()
        if (node.loaded) {
            unloadDescendants(conn, node)
            node.children = null
            node.loading = false
            conn.loadedCount--
        }
        fireStructureChanged(node)
    }

    // ------------------------------------------------------------- events

    private fun listenersList(): Array<TreeModelListener> =
        listeners.getListeners(TreeModelListener::class.java)

    private fun fireNodesChanged(node: Any) {
        val event = TreeModelEvent(this, pathOf(node))
        listenersList().forEach { it.treeNodesChanged(event) }
    }

    private fun fireNodesInserted(parent: Any, indices: IntArray, children: Array<Any?>) {
        val event = TreeModelEvent(this, pathOf(parent), indices, children)
        listenersList().forEach { it.treeNodesInserted(event) }
    }

    private fun fireNodesRemoved(parent: Any, indices: IntArray, children: Array<Any?>) {
        val event = TreeModelEvent(this, pathOf(parent), indices, children)
        listenersList().forEach { it.treeNodesRemoved(event) }
    }

    private fun fireStructureChanged(node: Any) {
        val event = TreeModelEvent(this, pathOf(node))
        listenersList().forEach { it.treeStructureChanged(event) }
    }
}
