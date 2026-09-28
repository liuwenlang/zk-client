package wiki.twom.plugin.zk

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.Toggleable
import com.intellij.openapi.project.DumbAware
import javax.swing.Icon
import javax.swing.JComponent

object ZkActions {

    /** Place ids passed to createActionToolbar/createActionPopupMenu, so presentations can
     *  tell whether they render in the toolbar (icons) or the popup (platform checkmarks). */
    const val TOOLBAR_PLACE = "ZooKeeperToolbar"
    const val POPUP_PLACE = "ZooKeeperTreePopup"


    fun toolbarGroup(panel: ZkPanel): DefaultActionGroup = DefaultActionGroup(
        AddConnectionAction(panel),
        EditConnectionAction(panel),
        RemoveConnectionAction(panel),
        Separator.getInstance(),
        ConnectDisconnectAction(panel),
        RefreshAction(panel),
        Separator.getInstance(),
        CreateNodeAction(panel),
        DeleteNodeAction(panel),
        Separator.getInstance(),
        GoToPathAction(panel),
        SearchAction(panel),
        CopyPathAction(panel),
        WatchToggleAction(panel),
    )

    /** Keyboard shortcuts inside the tool window tree. */
    fun installShortcuts(panel: ZkPanel, component: JComponent) {
        CreateNodeAction(panel).registerCustomShortcutSet(CustomShortcutSet.fromString("INSERT"), component)
        DeleteNodeAction(panel).registerCustomShortcutSet(CustomShortcutSet.fromString("DELETE"), component)
        RefreshAction(panel).registerCustomShortcutSet(CustomShortcutSet.fromString("F5"), component)
    }
}

private abstract class ZkPanelAction(
    protected val panel: ZkPanel,
    text: String,
    description: String = text,
    icon: Icon? = null,
) : AnAction(text, description, icon), DumbAware {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    protected fun connectedSelection(): ZkSelection? {
        val sel = panel.selection() ?: return null
        return if (panel.sessionOf(sel.conn)?.isConnected == true) sel else null
    }

    protected fun anySelection(): ZkSelection? = panel.selection()
}

private class AddConnectionAction(panel: ZkPanel) :
    ZkPanelAction(panel, "New Connection…", "Add a ZooKeeper connection", AllIcons.General.Add) {

    override fun actionPerformed(e: AnActionEvent) = panel.addConnection()
}

private class EditConnectionAction(panel: ZkPanel) :
    ZkPanelAction(panel, "Edit Connection…", "Edit the selected connection", AllIcons.Actions.Edit) {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = anySelection() != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        panel.selection()?.let { panel.editConnection(it.conn) }
    }
}

private class RemoveConnectionAction(panel: ZkPanel) :
    ZkPanelAction(panel, "Remove Connection…", "Remove the selected connection", AllIcons.General.Remove) {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = anySelection() != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        panel.selection()?.let { panel.removeConnection(it.conn) }
    }
}

private class ConnectDisconnectAction(panel: ZkPanel) :
    ZkPanelAction(panel, "Connect", "Connect / disconnect the selected connection", ZkIcons.connect) {

    override fun update(e: AnActionEvent) {
        val sel = anySelection()
        if (sel == null) {
            e.presentation.isEnabled = false
            return
        }
        e.presentation.isEnabled = true
        when (panel.sessionOf(sel.conn)?.state) {
            ZkSessionState.CONNECTED -> {
                e.presentation.text = "Disconnect"
                e.presentation.icon = ZkIcons.disconnect
            }

            else -> {
                e.presentation.text = "Connect"
                e.presentation.icon = ZkIcons.connect
            }
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val sel = panel.selection() ?: return
        if (panel.sessionOf(sel.conn)?.isConnected == true) panel.disconnect(sel.conn) else panel.connect(sel.conn)
    }
}

private class RefreshAction(panel: ZkPanel) :
    ZkPanelAction(panel, "Refresh", "Refresh the selected node", AllIcons.Actions.Refresh) {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = anySelection() != null
    }

    override fun actionPerformed(e: AnActionEvent) = panel.refreshSelection()
}

private class CreateNodeAction(panel: ZkPanel) :
    ZkPanelAction(panel, "New Node…", "Create a child znode under the selection", AllIcons.General.Add) {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = connectedSelection() != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val sel = connectedSelection() ?: return
        panel.createNode(sel.conn, sel.node)
    }
}

private class DeleteNodeAction(panel: ZkPanel) :
    ZkPanelAction(panel, "Delete Node…", "Delete the selected znode (recursively)", AllIcons.General.Remove) {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = connectedSelection()?.node != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val node = connectedSelection()?.node ?: return
        panel.deleteNode(panel.selection()!!.conn, node)
    }
}

private class GoToPathAction(panel: ZkPanel) :
    ZkPanelAction(panel, "Go to Path…", "Jump to an absolute znode path", ZkIcons.goTo) {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = connectedSelection() != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val sel = connectedSelection() ?: return
        val dialog = ZkGoToPathDialog(panel.project)
        if (!dialog.showAndGet()) return
        panel.navigateTo(sel.conn, dialog.path)
    }
}

private class SearchAction(panel: ZkPanel) :
    ZkPanelAction(panel, "Search Nodes…", "Recursively search node names under the selection", AllIcons.Actions.Find) {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = connectedSelection() != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val sel = connectedSelection() ?: return
        panel.searchUnder(sel.conn, sel.node)
    }
}

private class CopyPathAction(panel: ZkPanel) :
    ZkPanelAction(panel, "Copy Path", "Copy the selected node path", AllIcons.Actions.Copy) {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = anySelection()?.node != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        panel.selection()?.node?.let { panel.copyPath(it) }
    }
}

private class WatchToggleAction(private val actionPanel: ZkPanel) :
    ToggleAction("Watch", "Toggle watches for the selected connection", ZkIcons.watchOff),
    DumbAware {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        super.update(e) // keep the selected/pressed presentation
        e.presentation.isEnabled = actionPanel.selection()?.conn != null
        // eye = watches on, crossed-out eye = watches off — in toolbar and popup alike
        e.presentation.icon = if (isSelected(e)) ZkIcons.watch else ZkIcons.watchOff
        if (e.place == ZkActions.POPUP_PLACE) {
            // the eye icons already carry the on/off state, so suppress the New UI's
            // "selected toggle" chip (the blue tint square behind the icon)
            Toggleable.setSelected(e.presentation, false)
        }
    }

    override fun isSelected(e: AnActionEvent): Boolean =
        actionPanel.selection()?.conn?.config?.watchEnabled == true

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val sel = actionPanel.selection() ?: return
        actionPanel.setWatchEnabled(sel.conn, state)
    }
}
