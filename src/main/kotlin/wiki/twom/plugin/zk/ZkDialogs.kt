package wiki.twom.plugin.zk

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import org.apache.zookeeper.CreateMode
import org.apache.zookeeper.WatchedEvent
import org.apache.zookeeper.Watcher
import org.apache.zookeeper.ZooDefs
import org.apache.zookeeper.ZooKeeper
import org.apache.zookeeper.data.ACL
import java.awt.Component
import java.awt.FlowLayout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JComponent

class ZkConnectionDialog(project: Project?, original: ZkConnectionConfig?) : DialogWrapper(project) {

    private val nameField = JBTextField(original?.name ?: "")
    private val connectField = JBTextField(original?.connectString ?: "localhost:2181")
    private val timeoutField = JBTextField((original?.sessionTimeoutMs ?: ZkConstants.DEFAULT_SESSION_TIMEOUT_MS).toString())
    private val userField = JBTextField(original?.authUser ?: "")
    private val passwordField = JBPasswordField().apply { text = original?.authPassword ?: "" }
    private val watchBox =
        JBCheckBox(ZkBundle.message("zk.dialog.connection.watchEnabled"), original?.watchEnabled ?: false)
    private val autoReconnectBox = JBCheckBox(ZkBundle.message("zk.dialog.connection.autoReconnect"), original?.autoReconnect ?: true)
    private val testButton = JButton(ZkBundle.message("zk.dialog.connection.testButton"))
    private val testResultLabel = JBLabel()
    private val testPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
        add(testButton)
        add(testResultLabel)
        border = JBUI.Borders.empty(4, 0, 4, 0)
    }

    val config: ZkConnectionConfig = copyOf(original ?: ZkConnectionConfig())

    init {
        title = if (original == null) ZkBundle.message("zk.dialog.connection.new.title") else ZkBundle.message("zk.dialog.connection.edit.title")
        init()
    }

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent(ZkBundle.message("zk.dialog.connection.name"), nameField, 1, false)
        .addLabeledComponent(ZkBundle.message("zk.dialog.connection.connectString"), connectField, 1, false)
        .addTooltip(ZkBundle.message("zk.dialog.connection.connectString.tooltip"))
        .addLabeledComponent(ZkBundle.message("zk.dialog.connection.timeout"), timeoutField, 1, false)
        .addSeparator()
        .addLabeledComponent(ZkBundle.message("zk.dialog.connection.authUser"), userField, 1, false)
        .addTooltip(ZkBundle.message("zk.dialog.connection.authUser.tooltip"))
        .addLabeledComponent(ZkBundle.message("zk.dialog.connection.authPassword"), passwordField, 1, false)
        .addSeparator()
        // The test row must span the full width: FormBuilder.addComponent would park this panel
        // in the narrow label column of the row above, clipping the result label.
        .addLabeledComponent(ZkBundle.message("zk.dialog.connection.connectivity"), testPanel, 1, false)
        .addTooltip(ZkBundle.message("zk.dialog.connection.connectivity.tooltip"))
        .addSeparator()
        .addComponent(watchBox)
        .addComponent(autoReconnectBox)
        .panel

    override fun getPreferredFocusedComponent(): JComponent = connectField

    init {
        testButton.addActionListener { testConnection() }
    }

    private fun testConnection() {
        doValidate()?.let {
            // DialogWrapper only shows validation errors for its own buttons; surface ours here
            // instead of failing silently.
            testResultLabel.text = it.message
            testResultLabel.foreground = com.intellij.ui.JBColor.RED
            return
        }
        val connectString = connectField.text.trim()
        val timeout = timeoutField.text.trim().toIntOrNull() ?: ZkConstants.DEFAULT_SESSION_TIMEOUT_MS
        val user = userField.text.trim()
        val password = String(passwordField.password)
        testButton.isEnabled = false
        testResultLabel.text = ZkBundle.message("zk.dialog.connection.testing")
        ApplicationManager.getApplication().executeOnPooledThread {
            var client: ZooKeeper? = null
            val connected = try {
                val latch = CountDownLatch(1)
                client = ZooKeeper(connectString, timeout) { event: WatchedEvent ->
                    if (event.state == Watcher.Event.KeeperState.SyncConnected ||
                        event.state == Watcher.Event.KeeperState.AuthFailed
                    ) latch.countDown()
                }
                if (user.isNotBlank()) {
                    client.addAuthInfo("digest", "$user:$password".toByteArray())
                }
                latch.await(ZkConstants.CONNECTION_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS) &&
                        client.state == ZooKeeper.States.CONNECTED
            } catch (t: Throwable) {
                false
            } finally {
                try {
                    client?.close()
                } catch (_: Exception) {
                }
            }
            // any(): the label lives in this (modal) dialog — a default-modality invokeLater
            // would never run while it is open and the probe outcome would never show.
            ApplicationManager.getApplication().invokeLater({
                if (isDisposed) return@invokeLater
                testButton.isEnabled = true
                if (connected) {
                    testResultLabel.text = ZkBundle.message("zk.dialog.connection.testSuccess")
                    testResultLabel.foreground = com.intellij.ui.JBColor.GREEN
                } else {
                    testResultLabel.text = ZkBundle.message("zk.dialog.connection.testFailed")
                    testResultLabel.foreground = com.intellij.ui.JBColor.RED
                }
            }, ModalityState.any())
        }
    }

    override fun doValidate(): ValidationInfo? {
        val connect = connectField.text.trim()
        if (connect.isBlank()) return ValidationInfo(ZkBundle.message("zk.dialog.connection.validation.emptyConnectString"), connectField)
        if (!connect.contains(":")) return ValidationInfo(ZkBundle.message("zk.dialog.connection.validation.noPort"), connectField)
        connectStringHostIssue(connect)?.let { return ValidationInfo(it, connectField) }
        val timeout = timeoutField.text.trim().toIntOrNull()
            ?: return ValidationInfo(ZkBundle.message("zk.dialog.connection.validation.timeoutNotNumber"), timeoutField)
        if (timeout < ZkConstants.MIN_SESSION_TIMEOUT_MS || timeout > ZkConstants.MAX_SESSION_TIMEOUT_MS) {
            return ValidationInfo(
                ZkBundle.message("zk.dialog.connection.validation.timeoutOutOfRange",
                    ZkConstants.MIN_SESSION_TIMEOUT_MS, ZkConstants.MAX_SESSION_TIMEOUT_MS, ZkConstants.DEFAULT_SESSION_TIMEOUT_MS),
                timeoutField
            )
        }
        return null
    }

    override fun doOKAction() {
        config.name = nameField.text.trim()
        config.connectString = connectField.text.trim()
        config.sessionTimeoutMs = timeoutField.text.trim().toInt()
        config.authUser = userField.text.trim()
        config.authPassword = String(passwordField.password)
        config.watchEnabled = watchBox.isSelected
        config.autoReconnect = autoReconnectBox.isSelected
        super.doOKAction()
    }
}

private fun copyOf(source: ZkConnectionConfig): ZkConnectionConfig {
    val copy = ZkConnectionConfig()
    copy.id = source.id
    copy.name = source.name
    copy.connectString = source.connectString
    copy.sessionTimeoutMs = source.sessionTimeoutMs
    copy.authUser = source.authUser
    copy.authPassword = source.authPassword
    copy.watchEnabled = source.watchEnabled
    copy.autoReconnect = source.autoReconnect
    return copy
}

class ZkCreateNodeDialog(project: Project?, private val parentPath: String) : DialogWrapper(project) {

    enum class AclChoice(val labelKey: String, val acl: List<ACL>) {
        OPEN("zk.dialog.createNode.acl.open", ZooDefs.Ids.OPEN_ACL_UNSAFE),
        READONLY("zk.dialog.createNode.acl.readonly", ZooDefs.Ids.READ_ACL_UNSAFE),
        CREATOR("zk.dialog.createNode.acl.creator", ZooDefs.Ids.CREATOR_ALL_ACL),
    }

    private val nameField = JBTextField()
    private val modeCombo = JComboBox(
        arrayOf(
            CreateMode.PERSISTENT,
            CreateMode.PERSISTENT_SEQUENTIAL,
            CreateMode.EPHEMERAL,
            CreateMode.EPHEMERAL_SEQUENTIAL,
        )
    )
    private val aclCombo = JComboBox(arrayOf(AclChoice.OPEN, AclChoice.READONLY, AclChoice.CREATOR))
    private val dataArea = JBTextArea(8, 40)

    val nodeName: String get() = nameField.text.trim()
    val mode: CreateMode get() = modeCombo.selectedItem as? CreateMode ?: CreateMode.PERSISTENT
    val acl: List<ACL> get() = (aclCombo.selectedItem as? AclChoice ?: AclChoice.OPEN).acl
    val data: ByteArray get() = dataArea.text.toByteArray()

    init {
        title = ZkBundle.message("zk.dialog.createNode.title", parentPath)
        modeCombo.renderer = TextRenderer { mode ->
            when (mode as? CreateMode) {
                CreateMode.PERSISTENT -> ZkBundle.message("zk.dialog.createNode.mode.persistent")
                CreateMode.PERSISTENT_SEQUENTIAL -> ZkBundle.message("zk.dialog.createNode.mode.persistentSequential")
                CreateMode.EPHEMERAL -> ZkBundle.message("zk.dialog.createNode.mode.ephemeral")
                CreateMode.EPHEMERAL_SEQUENTIAL -> ZkBundle.message("zk.dialog.createNode.mode.ephemeralSequential")
                else -> ""
            }
        }
        aclCombo.renderer = TextRenderer { (it as? AclChoice)?.let { choice -> ZkBundle.message(choice.labelKey) } ?: "" }
        init()
    }

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent(ZkBundle.message("zk.dialog.createNode.parent"), JBTextField(parentPath).apply { isEditable = false })
        .addLabeledComponent(ZkBundle.message("zk.dialog.createNode.name"), nameField, 1, false)
        .addLabeledComponent(ZkBundle.message("zk.dialog.createNode.mode"), modeCombo, 1, false)
        .addLabeledComponent(ZkBundle.message("zk.dialog.createNode.acl"), aclCombo, 1, false)
        .addLabeledComponentFillVertically(ZkBundle.message("zk.dialog.createNode.data"), dataArea)
        .panel

    override fun getPreferredFocusedComponent(): JComponent = nameField

    override fun doValidate(): ValidationInfo? {
        val name = nameField.text.trim()
        if (name.isEmpty()) return ValidationInfo(ZkBundle.message("zk.dialog.createNode.validation.emptyName"), nameField)
        if (name.contains('/')) return ValidationInfo(ZkBundle.message("zk.dialog.createNode.validation.slashInName"), nameField)
        return null
    }
}

class ZkGoToPathDialog(project: Project?) : DialogWrapper(project) {

    private val pathField = JBTextField("/")

    val path: String get() = pathField.text.trim()

    init {
        title = ZkBundle.message("zk.dialog.goToPath.title")
        init()
    }

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent(ZkBundle.message("zk.dialog.goToPath.path"), pathField, 1, false)
        .addTooltip(ZkBundle.message("zk.dialog.goToPath.tooltip"))
        .panel

    override fun getPreferredFocusedComponent(): JComponent = pathField

    override fun doValidate(): ValidationInfo? {
        val error = ZkPaths.validate(pathField.text.trim())
        return error?.let { ValidationInfo(it, pathField) }
    }
}

class ZkSearchDialog(project: Project?, private val scope: String) : DialogWrapper(project) {

    private val queryField = JBTextField()

    val query: String get() = queryField.text.trim()

    init {
        title = ZkBundle.message("zk.dialog.search.title", scope)
        init()
    }

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent(ZkBundle.message("zk.dialog.search.query"), queryField, 1, false)
        .addTooltip(ZkBundle.message("zk.dialog.search.tooltip"))
        .panel

    override fun getPreferredFocusedComponent(): JComponent = queryField

    override fun doValidate(): ValidationInfo? =
        if (query.isEmpty()) ValidationInfo(ZkBundle.message("zk.dialog.search.validation.emptyQuery"), queryField) else null
}

private class TextRenderer(private val textOf: (Any?) -> String) : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>?, value: Any?, index: Int, selected: Boolean, hasFocus: Boolean,
    ): Component {
        val component = super.getListCellRendererComponent(list, value, index, selected, hasFocus)
        this.text = textOf(value)
        return component
    }
}
