package wiki.twom.plugin.zk

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import org.apache.zookeeper.KeeperException
import org.apache.zookeeper.ZooDefs
import org.apache.zookeeper.data.Stat
import java.awt.BorderLayout
import java.awt.Font
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants

/** Right-hand side of the tool window: node data editor + stat table. */
class ZkDetailsPanel(
    private val project: Project,
    private val sessions: ZkSessionManager,
    private val onSaved: (connId: String, path: String) -> Unit,
) {

    private val dataArea = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
    }
    private val dataSizeLabel = JLabel(ZkBundle.message("zk.details.size.bytes", 0))
    private val reloadButton = JButton(ZkBundle.message("zk.details.button.reload"))
    private val formatButton = JButton(ZkBundle.message("zk.details.button.formatJson"))
    private val saveButton = JButton(ZkBundle.message("zk.details.button.save")).apply { isEnabled = false }
    private val dataButtons = JPanel().apply {
        add(dataSizeLabel)
        add(reloadButton)
        add(formatButton)
        add(saveButton)
    }

    private val statPanel = JPanel(BorderLayout())
    private val aclPanel = JPanel(BorderLayout())
    private val tabs = JBTabbedPane()

    val component: JComponent = tabs

    private var connId: String? = null
    private var path: String? = null
    private var version: Int = -1
    private var binary = false
    private var dirty = false

    init {
        val dataPanel = JPanel(BorderLayout()).apply {
            add(dataButtons, BorderLayout.NORTH)
            add(
                JScrollPane(
                    dataArea,
                    ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                    ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED,
                ),
                BorderLayout.CENTER,
            )
        }
        tabs.addTab(ZkBundle.message("zk.details.tab.data"), dataPanel)
        tabs.addTab(ZkBundle.message("zk.details.tab.stat"), JScrollPane(statPanel))
        tabs.addTab(ZkBundle.message("zk.details.tab.acl"), JScrollPane(aclPanel))

        dataArea.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = markDirty()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = markDirty()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = markDirty()
        })

        reloadButton.addActionListener { reloadSelected() }
        formatButton.addActionListener { formatJson() }
        saveButton.addActionListener { save() }
    }

    fun showConnection(config: ZkConnectionConfig, state: ZkSessionState, sessionId: Long, sessionTimeoutMs: Int) {
        connId = null
        path = null
        setEditingEnabled(false)
        dataArea.text = ""
        dataSizeLabel.text = formatBytes(0)
        statPanel.removeAll()
        val stateKey = when (state) {
            ZkSessionState.CONNECTED -> "zk.state.connected"
            ZkSessionState.CONNECTING -> "zk.state.connecting"
            ZkSessionState.DISCONNECTED -> "zk.state.disconnected"
            ZkSessionState.EXPIRED -> "zk.state.expired"
            ZkSessionState.AUTH_FAILED -> "zk.state.authFailed"
        }
        statPanel.add(statForm(listOfNotNull(
            ZkBundle.message("zk.details.connection.label") to config.connectString,
            ZkBundle.message("zk.details.connection.state") to ZkBundle.message(stateKey),
            ZkBundle.message("zk.details.connection.sessionId") to (if (sessionId != 0L) "0x%016x".format(sessionId) else "-"),
            ZkBundle.message("zk.details.connection.sessionTimeout") to "$sessionTimeoutMs ms",
            ZkBundle.message("zk.details.connection.watchMode") to ZkBundle.message(if (config.watchEnabled) "zk.details.connection.enabled" else "zk.details.connection.disabled"),
            ZkBundle.message("zk.details.connection.autoReconnect") to ZkBundle.message(if (config.autoReconnect) "zk.details.connection.enabled" else "zk.details.connection.disabled"),
        )), BorderLayout.CENTER)
        statPanel.revalidate()
        statPanel.repaint()
        aclPanel.removeAll()
        aclPanel.revalidate()
        aclPanel.repaint()
    }

    fun showNode(connectionId: String, nodePath: String, data: ZkSession.NodeData) {
        connId = connectionId
        path = nodePath
        version = data.stat.version
        binary = false
        setEditingEnabled(true)
        val dataSize = data.data?.size ?: 0
        dataSizeLabel.text = formatBytes(dataSize)
        val text = decode(data.data)
        if (text == null) {
            binary = true
            dataArea.text = ""
            dataArea.isEditable = false
            dataArea.text = "(binary data, ${formatBytes(dataSize)} — hex preview)\n\n" + hexPreview(data.data)
            saveButton.isEnabled = false
        } else {
            dataArea.text = text
        }
        dirty = false
        saveButton.isEnabled = false
        renderStat(data.stat)
        loadAcl(connectionId, nodePath)
        tabs.selectedIndex = 0
    }

    fun showLoadFailed(nodePath: String, error: Throwable) {
        path = nodePath
        connId = null
        setEditingEnabled(false)
        dataArea.text = ZkBundle.message("zk.details.failed", nodePath, error.message ?: "")
        dataSizeLabel.text = formatBytes(0)
        statPanel.removeAll()
        statPanel.revalidate()
        statPanel.repaint()
        aclPanel.removeAll()
        aclPanel.revalidate()
        aclPanel.repaint()
    }

    fun clear() {
        connId = null
        path = null
        setEditingEnabled(false)
        dataArea.text = ""
        dataSizeLabel.text = formatBytes(0)
        statPanel.removeAll()
        aclPanel.removeAll()
    }

    private fun loadAcl(connectionId: String, nodePath: String) {
        val session = sessions.session(connectionId)
        if (session == null || !session.isConnected) {
            renderAcl(listOf(ZkBundle.message("zk.details.acl.notConnected") to "-"))
            return
        }
        sessions.async({ session.run { it.getACL(nodePath, Stat()) } }) { result ->
            if (connId != connectionId || path != nodePath) return@async
            result.onSuccess { acls ->
                renderAcl(
                    if (acls.isEmpty()) listOf(ZkBundle.message("zk.details.acl.empty") to "-")
                    else acls.map { "${it.id.scheme}:${it.id.id}" to aclPerms(it.perms) },
                )
            }.onFailure {
                if (connId == connectionId && path == nodePath) {
                    renderAcl(listOf(ZkBundle.message("zk.details.acl.loadFailed") to (it.message ?: "error")))
                }
            }
        }
    }

    private fun renderAcl(rows: List<Pair<String, String>>) {
        aclPanel.removeAll()
        aclPanel.add(statForm(rows), BorderLayout.CENTER)
        aclPanel.revalidate()
        aclPanel.repaint()
    }

    private fun reloadSelected() {
        val connectionId = connId ?: return
        val nodePath = path ?: return
        val session = sessions.session(connectionId) ?: return
        sessions.async({ session.readNode(nodePath, session.config.watchEnabled) }) { result ->
            result.onSuccess { showNode(connectionId, nodePath, it) }
                .onFailure { showLoadFailed(nodePath, it) }
        }
    }

    private fun formatJson() {
        if (binary || !dataArea.isEditable) return
        try {
            dataArea.text = JsonFormat.tryFormat(dataArea.text)
        } catch (e: IllegalArgumentException) {
            ZkUi.notifyWarning(project, ZkBundle.message("zk.notify.notValidJson"), e.message ?: ZkBundle.message("zk.notify.notValidJson.detail"))
        }
    }

    private fun save() {
        val connectionId = connId ?: return
        val nodePath = path ?: return
        if (binary) return
        val session = sessions.session(connectionId) ?: return
        val bytes = dataArea.text.toByteArray(StandardCharsets.UTF_8)
        val expectedVersion = version
        sessions.async({ session.run { it.setData(nodePath, bytes, expectedVersion) } }) { result ->
            result.onSuccess { stat ->
                version = stat.version
                dirty = false
                saveButton.isEnabled = false // editor state reset is the feedback
                onSaved(connectionId, nodePath)
            }.onFailure { error ->
                if (error is KeeperException.BadVersionException) {
                    ZkUi.notifyWarning(
                        project, ZkBundle.message("zk.notify.versionConflict"),
                        ZkBundle.message("zk.notify.versionConflict.detail", nodePath, expectedVersion)
                    )
                    reloadSelected()
                } else {
                    ZkUi.notifyError(project, ZkBundle.message("zk.notify.saveFailed"), error)
                }
            }
        }
    }

    private fun markDirty() {
        if (!dataArea.isEditable || binary) return
        dirty = true
        saveButton.isEnabled = path != null
    }

    private fun setEditingEnabled(value: Boolean) {
        dataArea.isEditable = value
        reloadButton.isEnabled = value
        formatButton.isEnabled = value
        saveButton.isEnabled = value && dirty
        if (!value) dirty = false
    }

    private fun renderStat(stat: Stat) {
        statPanel.removeAll()
        statPanel.add(statForm(listOf(
            ZkBundle.message("zk.details.stat.path") to (path ?: "-"),
            ZkBundle.message("zk.details.stat.ctime") to "${ZkTime.formatTime(stat.ctime)}  (czxid 0x${java.lang.Long.toHexString(stat.czxid)})",
            ZkBundle.message("zk.details.stat.mtime") to "${ZkTime.formatTime(stat.mtime)}  (mzxid 0x${java.lang.Long.toHexString(stat.mzxid)})",
            ZkBundle.message("zk.details.stat.dataVersion") to stat.version.toString(),
            ZkBundle.message("zk.details.stat.children") to "${stat.numChildren}  (cversion ${stat.cversion}, pzxid 0x${java.lang.Long.toHexString(stat.pzxid)})",
            ZkBundle.message("zk.details.stat.aclVersion") to stat.aversion.toString(),
            ZkBundle.message("zk.details.stat.ephemeralOwner") to if (stat.ephemeralOwner == 0L) ZkBundle.message("zk.details.stat.ephemeralOwner.persistent") else "0x${java.lang.Long.toHexString(stat.ephemeralOwner)}",
            ZkBundle.message("zk.details.stat.dataLength") to stat.dataLength.toString(),
        )), BorderLayout.CENTER)
        statPanel.revalidate()
        statPanel.repaint()
    }

    private fun statForm(rows: List<Pair<String, String>>): JComponent =
        FormBuilder.createFormBuilder()
            .apply { rows.forEach { addLabeledComponent(it.first + ":", javax.swing.JLabel(it.second)) } }
            .panel

    companion object {
        fun aclPerms(perms: Int): String {
            if (perms == ZooDefs.Perms.ALL) return ZkBundle.message("zk.details.acl.perms.all")
            return buildString {
                if (perms and ZooDefs.Perms.CREATE != 0) append('c')
                if (perms and ZooDefs.Perms.DELETE != 0) append('d')
                if (perms and ZooDefs.Perms.READ != 0) append('r')
                if (perms and ZooDefs.Perms.WRITE != 0) append('w')
                if (perms and ZooDefs.Perms.ADMIN != 0) append('a')
                if (isEmpty()) append(ZkBundle.message("zk.details.acl.perms.none"))
            }
        }

        private fun decode(data: ByteArray?): String? {
            if (data == null) return ""
            return try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(data))
                    .toString()
            } catch (_: java.nio.charset.CharacterCodingException) {
                null
            }
        }

        private fun hexPreview(data: ByteArray?): String {
            val bytes = data ?: return ""
            val limit = minOf(bytes.size, ZkConstants.HEX_PREVIEW_MAX_BYTES)
            val sb = StringBuilder()
            var i = 0
            while (i < limit) {
                val chunk = minOf(16, limit - i)
                sb.append("%08x  ".format(i))
                for (j in 0 until 16) {
                    if (j < chunk) sb.append("%02x ".format(bytes[i + j].toInt() and 0xff)) else sb.append("   ")
                    if (j == 7) sb.append(' ')
                }
                sb.append(" |")
                for (j in 0 until chunk) {
                    val b = bytes[i + j].toInt() and 0xff
                    sb.append(if (b in 32..126) b.toChar() else '.')
                }
                sb.append("|\n")
                i += chunk
            }
            if (limit < bytes.size) sb.append(ZkBundle.message("zk.details.binary.more", formatBytes(bytes.size - limit)) + "\n")
            return sb.toString()
        }

        private fun formatBytes(bytes: Int): String = when {
            bytes < 1024 -> ZkBundle.message("zk.details.size.bytes", bytes)
            bytes < 1024 * 1024 -> ZkBundle.message("zk.details.size.kb", "%.1f".format(bytes / 1024.0))
            else -> ZkBundle.message("zk.details.size.mb", "%.2f".format(bytes / (1024.0 * 1024.0)))
        }
    }
}
