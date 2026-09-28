package wiki.twom.plugin.zk

import org.apache.zookeeper.data.Stat
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date

object ZkTime {
    private val FORMAT = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    }

    fun formatTime(millis: Long): String =
        if (millis <= 0) "-" else FORMAT.get()!!.format(Date(millis))
}

/** Pure diff between two sorted child-name lists. */
data class ChildrenDiff(val removed: List<String>, val added: List<String>) {
    val isEmpty: Boolean get() = removed.isEmpty() && added.isEmpty()
}

fun diffChildren(old: List<String>, new: List<String>): ChildrenDiff {
    if (old === new) return ChildrenDiff(emptyList(), emptyList())
    val oldSet = HashSet(old)
    val newSet = HashSet(new)
    return ChildrenDiff(old.filter { it !in newSet }, new.filter { it !in oldSet })
}

/** State of an in-flight streamed bulk insert; see [ZkTreeModel.applyChildrenStreamed]. */
internal class ChunkState {
    @Volatile
    var cancelled = false
}

/**
 * Pure data node of the znode tree. No Swing types — the UI layer ([ZkTreeModel]) is the
 * only mutator and only on the EDT; worker threads may read [path] (idempotent lazy cache).
 *
 * Memory profile for huge trees:
 *  - stores the child *name*, not the absolute path; [path] is derived and cached lazily
 *  - children are kept sorted by name, so lookup is a binary search (no per-node index maps)
 *  - childless leaves share one immutable empty list instead of an ArrayList each
 */
class ZkNode(val name: String, val parent: ZkNode?, private val owner: ZkConnectionNode?) {

    val connection: ZkConnectionNode?
        get() = owner ?: parent?.connection

    val isRootZnode: Boolean get() = parent == null

    @Volatile
    private var pathCache: String? = null

    val path: String
        get() = pathCache ?: computePath().also { pathCache = it }

    @Volatile
    var stat: Stat? = null

    /**
     * Whether this node has been confirmed to have children.
     * null = unknown, true = has children, false = is leaf
     */
    @Volatile
    var hasChildren: Boolean? = null

    /** null = never loaded; [EMPTY_CHILDREN] = loaded, childless. */
    @Volatile
    var children: MutableList<ZkNode>? = null

    val loaded: Boolean get() = children != null

    @Volatile
    var loading = false

    @Volatile
    internal var chunkState: ChunkState? = null

    fun cancelChunk() {
        chunkState?.cancelled = true
        chunkState = null
    }

    private fun computePath(): String = when {
        parent == null -> "/"
        parent.parent == null -> "/$name"
        else -> parent.path + "/" + name
    }

    fun tooltip(): String {
        val s = stat ?: return path
        val ephemeral = if (s.ephemeralOwner != 0L) "ephemeral, " else ""
        return "$path\n${ephemeral}${s.dataLength} bytes, ${s.numChildren} children\n" +
                "mtime: ${ZkTime.formatTime(s.mtime)} (version ${s.version})"
    }

    // ------------------------------------------------------------- child access (EDT mutations)

    val childCount: Int get() = children?.size ?: 0

    fun childAt(index: Int): ZkNode = children!![index]

    fun childNamed(name: String): ZkNode? {
        val list = children ?: return null
        val idx = binarySearch(list, name)
        return if (idx >= 0) list[idx] else null
    }

    fun indexOfChildNamed(name: String): Int {
        val list = children ?: return -1
        val idx = binarySearch(list, name)
        return if (idx >= 0) idx else -1
    }

    /** Inserts [node] keeping name order; returns the insertion index. */
    fun insertChildSorted(node: ZkNode): Int {
        val list = mutableChildren()
        var idx = binarySearch(list, node.name)
        if (idx >= 0) {
            list[idx] = node
            return idx
        }
        idx = -(idx + 1)
        list.add(idx, node)
        return idx
    }

    fun setAllChildren(nodes: List<ZkNode>) {
        children = if (nodes.isEmpty()) EMPTY_CHILDREN else ArrayList(nodes)
    }

    /** Returns a mutable, non-shared backing list, replacing the shared empty marker if needed. */
    fun mutableChildren(): ArrayList<ZkNode> {
        val current = children
        val list = when {
            current == null -> ArrayList()
            current === EMPTY_CHILDREN -> ArrayList()
            current is ArrayList -> current
            else -> ArrayList(current)
        }
        children = list
        return list
    }

    private fun binarySearch(list: List<ZkNode>, name: String): Int {
        var low = 0
        var high = list.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val cmp = list[mid].name.compareTo(name)
            if (cmp < 0) low = mid + 1 else if (cmp > 0) high = mid - 1 else return mid
        }
        return -(low + 1)
    }

    companion object {
        /** Shared and never mutated directly: childless leaves skip a per-node ArrayList. */
        val EMPTY_CHILDREN: MutableList<ZkNode> = Collections.emptyList()
    }
}

/** Data-side root of one connection: config + session state + the "/" znode. */
class ZkConnectionNode(val config: ZkConnectionConfig) {

    @Volatile
    var sessionState: ZkSessionState = ZkSessionState.DISCONNECTED

    @Volatile
    var sessionId: Long = 0

    /** The "/" znode; recreated on every (re)connect. */
    @Volatile
    var root: ZkNode = ZkNode("", null, this)

    /** Number of nodes holding a loaded child list; mutated on the EDT only. */
    var loadedCount: Int = 0

    val stateSuffix: String
        get() = when (sessionState) {
            ZkSessionState.CONNECTED -> "connected"
            ZkSessionState.CONNECTING -> "connecting…"
            ZkSessionState.EXPIRED -> "expired"
            ZkSessionState.AUTH_FAILED -> "auth failed"
            ZkSessionState.DISCONNECTED -> "disconnected"
        }

    override fun toString(): String =
        if (sessionState == ZkSessionState.DISCONNECTED) config.displayName
        else "${config.displayName} ($stateSuffix)"

    fun tooltip(): String = buildString {
        append(config.connectString)
        if (config.hasAuth) append("\nauth: digest (").append(config.authUser).append(')')
        append("\nsession timeout: ").append(config.sessionTimeoutMs).append(" ms")
        append("\nwatches: ").append(if (config.watchEnabled) "on" else "off")
        append("\nloaded node lists: ").append(loadedCount)
    }
}
