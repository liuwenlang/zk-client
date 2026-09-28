package wiki.twom.plugin.zk

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import org.apache.zookeeper.KeeperException
import org.apache.zookeeper.Watcher
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.datatransfer.StringSelection
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTree
import javax.swing.ToolTipManager
import javax.swing.tree.TreePath

data class ZkSelection(val conn: ZkConnectionNode, val node: ZkNode?)

/** Builds and drives the ZooKeeper tool window content: toolbar + tree + details. */
class ZkPanel(val project: Project) {

    private val connectionManager = ZkConnectionManager.getInstance(project)
    private val sessions = ZkSessionManager.getInstance(project)
    private val model = ZkTreeModel()
    private val tree: Tree
    private val details = ZkDetailsPanel(project, sessions) { connId, path -> onSavedFromEditor(connId, path) }

    /** Coalesces child-refresh watch storms into one EDT pass. Key is "connId|path". */
    private val pendingChildRefresh = LinkedHashSet<String>()

    /** Timer for auto-refreshing expanded nodes to detect child changes. */
    private var autoRefreshTimer: java.util.Timer? = null

    private var evicting = false

    val component: JPanel

    init {
        tree = object : Tree(model) {
            override fun getToolTipText(event: MouseEvent): String? {
                return when (val node = getPathForLocation(event.x, event.y)?.lastPathComponent) {
                    is ZkConnectionNode -> node.tooltip()
                    is ZkNode -> node.tooltip()
                    else -> null
                }
            }
        }.apply {
            isRootVisible = false
            showsRootHandles = true
            rowHeight = JBUI.scale(22) // fixed height: JTree uses the cheap fixed-row cache
            isLargeModel = true        // child access stays lazy for huge sibling lists
            cellRenderer = ZkTreeCellRenderer()
            emptyText.setText(ZkBundle.message("zk.emptyText"))
            ToolTipManager.sharedInstance().registerComponent(this)
        }
        TreeSpeedSearch.installOn(tree, true) { path: TreePath ->
            when (val node = path.lastPathComponent) {
                is ZkNode -> node.name
                is ZkConnectionNode -> node.config.displayName
                else -> ""
            }
        }

        tree.addTreeWillExpandListener(object : javax.swing.event.TreeWillExpandListener {
            override fun treeWillExpand(event: javax.swing.event.TreeExpansionEvent) {
                when (val node = event.path.lastPathComponent) {
                    is ZkConnectionNode -> ensureConnectionExpanded(node)
                    is ZkNode -> if (!node.loaded) node.connection?.let { loadChildren(it, node) }
                }
            }

            override fun treeWillCollapse(event: javax.swing.event.TreeExpansionEvent) {}
        })

        tree.addTreeExpansionListener(object : javax.swing.event.TreeExpansionListener {
            override fun treeExpanded(event: javax.swing.event.TreeExpansionEvent) {}

            override fun treeCollapsed(event: javax.swing.event.TreeExpansionEvent) {
                val target = when (val node = event.path.lastPathComponent) {
                    is ZkConnectionNode -> node.root
                    is ZkNode -> node
                    else -> null
                } ?: return
                target.connection?.let { maybeEvict(it, target) }
            }
        })

        tree.addTreeSelectionListener { onSelectionChanged() }

        // double-click on a disconnected/expired connection (re)connects it
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2) return
                (tree.getPathForLocation(e.x, e.y)?.lastPathComponent as? ZkConnectionNode)?.let(::maybeAutoConnect)
            }
        })

        val toolbar: ActionToolbar = ActionManager.getInstance().createActionToolbar(
            ZkActions.TOOLBAR_PLACE, ZkActions.toolbarGroup(this), true,
        )
        toolbar.targetComponent = tree
        tree.componentPopupMenu = ActionManager.getInstance()
            .createActionPopupMenu(ZkActions.POPUP_PLACE, ZkActions.toolbarGroup(this)).component
        ZkActions.installShortcuts(this, tree)

        component = JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.NORTH)
            add(
                OnePixelSplitter(false, 0.55f).apply {
                    firstComponent = JScrollPane(tree)
                    secondComponent = details.component
                },
                BorderLayout.CENTER,
            )
        }

        connectionManager.connections.forEach { model.addConnection(ZkConnectionNode(it)) }
        restoreSelection()
        sessions.eventHandler = ::onSessionEvent

        // Start auto-refresh timer for expanded nodes
        startAutoRefresh()
    }

    fun dispose() {
        sessions.eventHandler = null
        stopAutoRefresh()
    }

    // ------------------------------------------------------------- auto refresh

    private fun startAutoRefresh() {
        stopAutoRefresh()
        autoRefreshTimer = java.util.Timer("ZkAutoRefresh", true).apply {
            scheduleAtFixedRate(object : java.util.TimerTask() {
                override fun run() {
                    refreshExpandedNodes()
                }
            }, ZkConstants.AUTO_REFRESH_INTERVAL_MS, ZkConstants.AUTO_REFRESH_INTERVAL_MS)
        }
    }

    private fun stopAutoRefresh() {
        autoRefreshTimer?.cancel()
        autoRefreshTimer = null
    }

    private fun refreshExpandedNodes() {
        ApplicationManager.getApplication().invokeLater({
            // Find all expanded nodes with active connections
            val toRefresh = mutableListOf<Pair<ZkConnectionNode, ZkNode>>()

            for (conn in model.connectionNodes()) {
                val session = sessions.session(conn.config.id)
                if (session == null || !session.isConnected || !session.config.watchEnabled) continue

                // Check if connection is expanded
                val connPath = model.pathOf(conn)
                if (!tree.isExpanded(connPath)) continue

                // Collect all expanded loaded nodes under this connection
                collectExpandedNodes(conn, conn.root, toRefresh)
            }

            // Refresh collected nodes asynchronously
            for ((conn, node) in toRefresh) {
                if (node.loaded) {
                    refreshChildren(conn, node.path)
                }
            }
        }, ModalityState.any())
    }

    private fun collectExpandedNodes(conn: ZkConnectionNode, node: ZkNode, result: MutableList<Pair<ZkConnectionNode, ZkNode>>) {
        val nodePath = model.pathOf(node)
        if (!tree.isExpanded(nodePath)) return

        result.add(conn to node)

        if (node.loaded) {
            node.children?.forEach { child ->
                collectExpandedNodes(conn, child, result)
            }
        }
    }

    // ------------------------------------------------------------- tree access

    fun selection(): ZkSelection? = when (val node = tree.lastSelectedPathComponent) {
        is ZkConnectionNode -> ZkSelection(node, null)
        is ZkNode -> node.connection?.let { ZkSelection(it, node) }
        else -> null
    }

    fun sessionOf(conn: ZkConnectionNode): ZkSession? = sessions.session(conn.config.id)

    private fun findNode(conn: ZkConnectionNode, path: String): ZkNode? {
        if (path == "/") return conn.root
        var current = conn.root
        for (segment in ZkPaths.segments(path)) {
            current = current.childNamed(segment) ?: return null
        }
        return current
    }

    private fun restoreSelection() {
        val id = connectionManager.lastSelectedId ?: return
        model.findConnection(id)?.let { tree.selectionPath = model.pathOf(it) }
    }

    // ------------------------------------------------------------- lazy loading

    private fun ensureConnectionExpanded(conn: ZkConnectionNode) {
        when (conn.sessionState) {
            ZkSessionState.CONNECTED -> if (!conn.root.loaded) loadChildren(conn, conn.root)
            // expanding a disconnected connection silently starts connecting it; children
            // load via the Connected event
            else -> maybeAutoConnect(conn)
        }
    }

    /** Starts connecting a disconnected/expired connection (expand, double-click, refresh). */
    private fun maybeAutoConnect(conn: ZkConnectionNode) {
        if (conn.sessionState == ZkSessionState.DISCONNECTED || conn.sessionState == ZkSessionState.EXPIRED) {
            connect(conn)
        }
    }

    fun loadChildren(conn: ZkConnectionNode, node: ZkNode) {
        if (node.loaded || node.loading) return
        val session = sessions.session(conn.config.id)
        if (session == null || !session.isConnected) return // state icon in the tree explains why
        node.loading = true
        model.notifyLoading(node) // reveal the placeholder row if visible
        val path = node.path
        sessions.async({ session.listChildren(path, session.config.watchEnabled) }) { result ->
            result.onSuccess { data ->
                model.applyChildrenStreamed(conn, node, data.names, data.stat)
                // Auto-detect if each child has children (async, non-blocking)
                if (data.names.isNotEmpty()) {
                    preloadChildrenInfo(conn, node)
                }
            }
                .onFailure {
                    node.loading = false
                    model.notifyLoading(node)
                    if (it is KeeperException.NoNodeException) {
                        ZkPaths.parentOf(path)?.let { parent -> refreshChildren(conn, parent) }
                    }
                    ZkUi.notifyError(project, ZkBundle.message("zk.notify.listFailed"), it)
                }
        }
    }

    /**
     * Preload numChildren info for immediate children to show correct icons
     * without requiring user to click each node.
     */
    private fun preloadChildrenInfo(conn: ZkConnectionNode, parent: ZkNode) {
        val session = sessions.session(conn.config.id) ?: return
        if (!session.isConnected) return

        sessions.async({
            val children = parent.children?.toList() ?: return@async emptyList()
            // Batch check children in background
            for (child in children) {
                if (child.hasChildren == null) {
                    try {
                        val stat = session.run { it.exists(child.path, false) }
                        child.hasChildren = stat != null && stat.numChildren > 0
                        child.stat = stat
                    } catch (e: Exception) {
                        // Ignore - node might be ephemeral and already gone
                    }
                }
            }
            children
        }) { result ->
            result.onSuccess { children ->
                // Notify tree to repaint with updated icons
                for (child in children) {
                    if (child.hasChildren != null) {
                        model.nodeChanged(child)
                    }
                }
            }
        }
    }

    /** Re-lists a node that is already loaded and patches it with a diff (watch / refresh). */
    private fun refreshChildren(conn: ZkConnectionNode, path: String) {
        val session = sessions.session(conn.config.id) ?: return
        if (!session.isConnected) return
        val node = findNode(conn, path) ?: return
        if (!node.loaded) return // will be fetched fresh on next expand
        sessions.async({ session.listChildren(path, session.config.watchEnabled) }) { result ->
            result.onSuccess { model.applyChildrenDiff(conn, node, it.names, it.stat) }
                .onFailure { ZkUi.notifyError(project, ZkBundle.message("zk.notify.listFailed"), it) }
        }
    }

    fun refreshSelection() {
        val sel = selection() ?: return
        if (sessionOf(sel.conn)?.isConnected != true) {
            maybeAutoConnect(sel.conn)
            return
        }
        val target = sel.node ?: sel.conn.root
        model.resetNode(sel.conn, target)
        loadChildren(sel.conn, target)
    }

    // ------------------------------------------------------------- session events

    private fun onSessionEvent(session: ZkSession, event: ZkSessionEvent) {
        val conn = model.findConnection(session.config.id)
        when (event) {
            is ZkSessionEvent.Connected -> {
                if (conn != null) {
                    conn.sessionState = ZkSessionState.CONNECTED
                    conn.sessionId = event.sessionId
                    model.connected(conn)
                    loadChildren(conn, conn.root) // prefetch "/" for an instant first expand
                }
                // no success balloon — the state icon in the tree is the feedback
            }

            is ZkSessionEvent.Disconnected -> conn?.let {
                it.sessionState = session.state
                it.sessionId = 0
                model.connectionStateChanged(it)
            }

            is ZkSessionEvent.ConnectTimeout -> {
                conn?.let {
                    it.sessionState = session.state
                    it.sessionId = 0
                    model.connectionStateChanged(it)
                }
                ZkUi.notifyWarning(
                    project, ZkBundle.message("zk.notify.connectionFailed"),
                    ZkBundle.message("zk.notify.connectionFailed.timeout", session.config.displayName),
                )
            }

            is ZkSessionEvent.Expired -> {
                conn?.let {
                    it.sessionState = ZkSessionState.EXPIRED
                    model.connectionStateChanged(it)
                }
                // with auto-reconnect the retry outcome reports itself (Connected stays silent,
                // a failed retry surfaces as a connection-failed warning); only a session that
                // stays dead needs a balloon
                if (!session.config.autoReconnect) {
                    ZkUi.notifyWarning(
                        project, ZkBundle.message("zk.notify.sessionExpired"),
                        ZkBundle.message("zk.notify.sessionExpired.detail", session.config.displayName, ".")
                    )
                }
            }

            is ZkSessionEvent.AuthFailed -> {
                conn?.let {
                    it.sessionState = ZkSessionState.AUTH_FAILED
                    model.connectionStateChanged(it)
                }
                ZkUi.notifyError(
                    project, ZkBundle.message("zk.notify.authFailed"),
                    IllegalStateException(ZkBundle.message("zk.notify.authFailed.detail", session.config.displayName)),
                )
            }

            is ZkSessionEvent.NodeChanged -> conn?.let { handleNodeEvent(it, event) }
        }
    }

    private fun handleNodeEvent(conn: ZkConnectionNode, event: ZkSessionEvent.NodeChanged) {
        when (event.type) {
            Watcher.Event.EventType.NodeChildrenChanged,
            Watcher.Event.EventType.NodeCreated,
            -> scheduleChildRefresh(conn.config.id, event.path)

            Watcher.Event.EventType.NodeDataChanged -> handleDataChanged(conn, event.path)

            Watcher.Event.EventType.NodeDeleted -> handleDeleted(conn, event.path)

            else -> {}
        }
    }

    private fun scheduleChildRefresh(connId: String, path: String) {
        val key = "$connId|$path"
        if (pendingChildRefresh.add(key)) {
            ApplicationManager.getApplication().invokeLater({
                val drained = pendingChildRefresh.toList()
                pendingChildRefresh.clear()
                for (item in drained) {
                    val sep = item.indexOf('|')
                    val conn = model.findConnection(item.substring(0, sep)) ?: continue
                    refreshChildren(conn, item.substring(sep + 1))
                }
            }, com.intellij.openapi.application.ModalityState.any()) // deliver under modal dialogs
        }
    }

    private fun handleDataChanged(conn: ZkConnectionNode, path: String) {
        val node = findNode(conn, path)
        val session = sessions.session(conn.config.id)
        if (node != null && session != null) {
            sessions.async({ session.exists(path, false) }) { result ->
                result.onSuccess { stat ->
                    if (stat == null) {
                        handleDeleted(conn, path)
                    } else {
                        node.stat = stat
                        model.nodeChanged(node)
                    }
                }
            }
        }
        if (selection()?.node?.path == path) onSelectionChanged() // re-read into the details pane
    }

    private fun handleDeleted(conn: ZkConnectionNode, path: String) {
        val selected = selection()?.node?.path
        if (selected != null && (selected == path || selected.startsWith("$path/"))) {
            details.clear()
        }
        val node = findNode(conn, path) ?: return
        if (node.isRootZnode) return // "/" cannot be deleted
        model.removeNode(node)
    }

    // ------------------------------------------------------------- selection -> details

    private fun onSelectionChanged() {
        when (val node = tree.lastSelectedPathComponent) {
            is ZkConnectionNode -> {
                connectionManager.lastSelectedId = node.config.id
                details.showConnection(node.config, node.sessionState, node.sessionId, node.config.sessionTimeoutMs)
            }

            is ZkNode -> showNodeDetails(node)

            else -> details.clear()
        }
    }

    private fun showNodeDetails(node: ZkNode) {
        val conn = node.connection
        if (conn == null) {
            details.clear()
            return
        }
        val session = sessions.session(conn.config.id)
        if (session == null || !session.isConnected) {
            details.showLoadFailed(node.path, IllegalStateException("Not connected"))
            return
        }
        val path = node.path
        sessions.async({ session.readNode(path, session.config.watchEnabled) }) { result ->
            if (selection()?.node?.path != path) return@async
            result.onSuccess { data ->
                node.stat = data.stat
                model.nodeChanged(node)
                details.showNode(conn.config.id, path, data)
            }.onFailure { error ->
                if (error is KeeperException.NoNodeException) {
                    details.showLoadFailed(path, IllegalStateException("Node no longer exists"))
                    handleDeleted(conn, path)
                } else {
                    details.showLoadFailed(path, error)
                }
            }
        }
    }

    private fun onSavedFromEditor(connId: String, path: String) {
        val conn = model.findConnection(connId) ?: return
        val node = findNode(conn, path) ?: return
        val session = sessions.session(connId) ?: return
        sessions.async({ session.exists(path, false) }) { result ->
            result.onSuccess { stat ->
                if (stat != null) {
                    node.stat = stat
                    model.nodeChanged(node)
                }
            }
        }
    }

    // ------------------------------------------------------------- eviction

    private fun maybeEvict(conn: ZkConnectionNode, node: ZkNode) {
        if (evicting || conn.loadedCount <= ZkConstants.EVICT_LOADED_NODES_THRESHOLD) return
        evicting = true
        try {
            // forget expansion state below the collapsed node so re-expands re-fetch lazily
            val expanded = tree.getExpandedDescendants(model.pathOf(node))
            if (expanded != null) {
                while (expanded.hasMoreElements()) tree.collapsePath(expanded.nextElement())
            }
            model.unloadDescendants(conn, node) // pure housekeeping, no balloon
        } finally {
            evicting = false
        }
    }

    // ------------------------------------------------------------- operations invoked from actions

    fun connect(conn: ZkConnectionNode) {
        val session = sessions.getOrCreate(conn.config)
        conn.sessionState = ZkSessionState.CONNECTING
        model.connectionStateChanged(conn)
        sessions.async({ session.connect() }) { result ->
            result.onFailure {
                // connect() threw (e.g. invalid connect string): no event will reset the
                // badge, so do it here or the UI stays "connecting…" forever.
                conn.sessionState = ZkSessionState.DISCONNECTED
                conn.sessionId = 0
                model.connectionStateChanged(conn)
                ZkUi.notifyError(project, ZkBundle.message("zk.notify.connectionFailed"), it)
            }
        }
    }

    fun disconnect(conn: ZkConnectionNode) {
        val session = sessions.session(conn.config.id) ?: return
        sessions.async({ session.disconnect() }) { }
        conn.sessionState = ZkSessionState.DISCONNECTED
        conn.sessionId = 0
        model.connectionStateChanged(conn)
    }

    fun addConnection() {
        val dialog = ZkConnectionDialog(project, null)
        if (!dialog.showAndGet()) return
        connectionManager.add(dialog.config)
        val conn = ZkConnectionNode(dialog.config)
        model.addConnection(conn)
        connect(conn)
    }

    fun editConnection(conn: ZkConnectionNode) {
        val dialog = ZkConnectionDialog(project, conn.config)
        if (!dialog.showAndGet()) return
        sessions.remove(dialog.config.id) // drop the session so new settings take effect
        connectionManager.update(dialog.config)
        model.replaceConnection(conn, ZkConnectionNode(dialog.config))
    }

    fun removeConnection(conn: ZkConnectionNode) {
        val ok = Messages.showOkCancelDialog(
            project,
            ZkBundle.message("zk.dialog.connection.remove.message", conn.config.displayName),
            ZkBundle.message("zk.dialog.connection.remove.title"),
            Messages.getOkButton(), Messages.getCancelButton(),
            AllIcons.General.Warning,
        )
        if (ok != Messages.OK) return
        sessions.remove(conn.config.id)
        connectionManager.remove(conn.config.id)
        model.removeConnection(conn)
    }

    fun createNode(conn: ZkConnectionNode, parent: ZkNode?) {
        val session = sessions.session(conn.config.id)
        if (session == null || !session.isConnected) return // the action is disabled while disconnected
        val parentPath = parent?.path ?: "/"
        val dialog = ZkCreateNodeDialog(project, parentPath)
        if (!dialog.showAndGet()) return
        val fullPath = ZkPaths.childPath(parentPath, dialog.nodeName)
        sessions.async({ session.run { it.create(fullPath, dialog.data, dialog.acl, dialog.mode) } }) { result ->
            result.onSuccess { actualPath -> navigateTo(conn, actualPath) } // navigation is the feedback
                .onFailure { ZkUi.notifyError(project, ZkBundle.message("zk.notify.createFailed"), it) }
        }
    }

    fun deleteNode(conn: ZkConnectionNode, node: ZkNode) {
        val session = sessions.session(conn.config.id)
        if (session == null || !session.isConnected) return // the action is disabled while disconnected
        val path = node.path

        // First, get the child count in background
        sessions.async({ session.run { client ->
            try {
                client.getChildren(path, false).size
            } catch (e: KeeperException.NoNodeException) {
                -1
            }
        } }) { result ->
            result.onFailure { ZkUi.notifyError(project, ZkBundle.message("zk.notify.deleteFailed"), it); return@async }
            val childCount = result.getOrNull() ?: 0
            if (childCount < 0) {
                handleDeleted(conn, path) // vanished server-side; the tree already reflects it
                return@async
            }

            // Then show confirmation dialog on EDT (already invoked by async callback)
            val message = if (childCount > 0) {
                ZkBundle.message("zk.dialog.deleteNode.messageWithChildren", path, childCount)
            } else {
                ZkBundle.message("zk.dialog.deleteNode.message", path)
            }
            val answer = Messages.showYesNoDialog(project, message, ZkBundle.message("zk.dialog.deleteNode.title"), AllIcons.General.Warning)
            if (answer != Messages.YES) return@async

            // Finally, perform the deletion with progress indicator
            object : Task.Backgroundable(project, ZkBundle.message("zk.dialog.deleteNode.progress", path), true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.text = ZkBundle.message("zk.dialog.deleteNode.progress", path)
                    session.run { client -> deleteRecursively(client, path, indicator) }
                }

                override fun onSuccess() {
                    handleDeleted(conn, path) // the tree update is the feedback
                }

                override fun onThrowable(error: Throwable) {
                    ZkUi.notifyError(project, ZkBundle.message("zk.notify.deleteFailed"), error)
                }
            }.queue()
        }
    }

    private fun deleteRecursively(client: org.apache.zookeeper.ZooKeeper, path: String, indicator: ProgressIndicator? = null) {
        indicator?.checkCanceled()
        val children = client.getChildren(path, false)
        for (child in children) {
            indicator?.checkCanceled()
            deleteRecursively(client, ZkPaths.childPath(path, child), indicator)
        }
        indicator?.text2 = ZkBundle.message("zk.dialog.deleteNode.progressItem", path)
        client.delete(path, -1)
    }

    fun copyPath(node: ZkNode) {
        com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(StringSelection(node.path))
    }

    fun setWatchEnabled(conn: ZkConnectionNode, enabled: Boolean) {
        conn.config.watchEnabled = enabled
        connectionManager.update(conn.config) // the eye icon in the toolbar is the feedback
    }

    // ------------------------------------------------------------- navigation

    /** Recursive, cancellable name search under [scope] (whole connection when null). */
    fun searchUnder(conn: ZkConnectionNode, scope: ZkNode?) {
        val session = sessions.session(conn.config.id)
        if (session == null || !session.isConnected) return // the action is disabled while disconnected
        val scopePath = scope?.path ?: "/"
        val dialog = ZkSearchDialog(project, scopePath)
        if (!dialog.showAndGet()) return
        val query = dialog.query
        val matches = ArrayList<String>(64)
        object : Task.Backgroundable(project, ZkBundle.message("zk.dialog.search.progress", scopePath), true) {
            override fun run(indicator: ProgressIndicator) {
                val queue = ArrayDeque<String>()
                queue.add(scopePath)
                var visited = 0L
                while (queue.isNotEmpty() && matches.size < ZkConstants.SEARCH_RESULT_LIMIT) {
                    if (indicator.isCanceled) return
                    val path = queue.removeFirst()
                    val children = try {
                        session.run { it.getChildren(path, false) }
                    } catch (e: KeeperException.NoNodeException) {
                        continue // ephemeral vanished mid-search
                    }
                    visited++
                    if (visited and 0x7F == 0L) {
                        indicator.text = ZkBundle.message("zk.dialog.search.progress.detail", visited, matches.size)
                    }
                    for (name in children) {
                        val childPath = ZkPaths.childPath(path, name)
                        if (matches.size < ZkConstants.SEARCH_RESULT_LIMIT && name.contains(query, ignoreCase = true)) {
                            matches.add(childPath)
                        }
                        queue.addLast(childPath)
                    }
                }
            }

            override fun onSuccess() {
                showSearchResults(conn, query, matches)
            }
        }.queue()
    }

    private fun showSearchResults(conn: ZkConnectionNode, query: String, matches: List<String>) {
        if (matches.isEmpty()) {
            ZkUi.notifyInfo(project, ZkBundle.message("zk.notify.noMatches"), ZkBundle.message("zk.notify.noMatches.detail", query))
            return
        }
        if (matches.size >= ZkConstants.SEARCH_RESULT_LIMIT) {
            ZkUi.notifyWarning(project, ZkBundle.message("zk.notify.searchTruncated"), ZkBundle.message("zk.notify.searchTruncated.detail", ZkConstants.SEARCH_RESULT_LIMIT))
        }
        val sorted = matches.sortedBy { it.length }
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(sorted)
            .setTitle(ZkBundle.message("zk.dialog.search.results.title", query, sorted.size))
            .setItemChosenCallback { path -> navigateTo(conn, path) }
            .createPopup()
            .showInFocusCenter()
    }

    fun navigateTo(conn: ZkConnectionNode, path: String) {
        val session = sessions.session(conn.config.id) ?: return
        if (!session.isConnected) return
        sessions.async({
            session.exists(path, false) ?: throw IllegalArgumentException("$path does not exist")
            // load the ancestors' lists only — the target's own children stay lazy
            val levels = LinkedHashMap<String, ZkSession.ChildrenData>()
            var prefix = "/"
            levels[prefix] = session.listChildren(prefix, session.config.watchEnabled)
            ZkPaths.parentOf(path)?.let { parent ->
                for (segment in ZkPaths.segments(parent)) {
                    prefix = ZkPaths.childPath(prefix, segment)
                    levels[prefix] = session.listChildren(prefix, session.config.watchEnabled)
                }
            }
            levels
        }) { result ->
            result.onSuccess { levels -> applyNavigation(conn, path, levels) }
                .onFailure { ZkUi.notifyError(project, ZkBundle.message("zk.notify.cannotNavigate"), it) }
        }
    }

    private fun applyNavigation(conn: ZkConnectionNode, path: String, levels: Map<String, ZkSession.ChildrenData>) {
        var current = conn.root
        levels["/"]?.let { if (!current.loaded) model.applyChildren(conn, current, it.names, it.stat) }
        for (segment in ZkPaths.segments(path).dropLast(1)) {
            val child = current.childNamed(segment) ?: break
            levels[child.path]?.let { if (!child.loaded) model.applyChildren(conn, child, it.names, it.stat) }
            current = child
        }
        val target = findNode(conn, path)
        if (target == null) {
            ZkUi.notifyWarning(project, ZkBundle.message("zk.notify.notFound"), path)
            return
        }
        val targetPath = model.pathOf(target)
        tree.expandPath(targetPath)
        tree.selectionPath = targetPath
        tree.scrollPathToVisible(targetPath)
    }

    // ------------------------------------------------------------- renderer

    private inner class ZkTreeCellRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean
        ) {
            val textAttributes = if (selected) {
                SimpleTextAttributes.SELECTED_SIMPLE_CELL_ATTRIBUTES
            } else {
                SimpleTextAttributes.REGULAR_ATTRIBUTES
            }

            when (value) {
                is ZkConnectionNode -> {
                    icon = when (value.sessionState) {
                        ZkSessionState.CONNECTED -> ZkIcons.connected
                        ZkSessionState.CONNECTING -> ZkIcons.connecting
                        ZkSessionState.EXPIRED -> ZkIcons.expired
                        ZkSessionState.AUTH_FAILED -> ZkIcons.authFailed
                        else -> ZkIcons.disconnected
                    }
                    append(value.config.displayName, textAttributes)
                }

                is ZkNode -> {
                    val displayName = value.name.ifEmpty { ZkBundle.message("zk.tree.root") }
                    icon = when {
                        value.hasChildren == false -> AllIcons.FileTypes.Text
                        value.hasChildren == true -> AllIcons.Nodes.Folder
                        value.loaded && value.childCount == 0 -> AllIcons.FileTypes.Text
                        else -> AllIcons.Nodes.Folder
                    }
                    append(displayName, textAttributes)
                }

                LoadingPlaceholder -> {
                    append(ZkBundle.message("zk.tree.loading"), textAttributes)
                }
            }
        }
    }
}
