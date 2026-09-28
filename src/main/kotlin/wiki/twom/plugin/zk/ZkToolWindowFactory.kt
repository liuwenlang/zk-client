package wiki.twom.plugin.zk

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class ZkToolWindowFactory : ToolWindowFactory {

    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ZkPanel(project)
        val content = ContentFactory.getInstance().createContent(panel.component, null, false)
        content.setDisposer { panel.dispose() }
        toolWindow.contentManager.addContent(content)
    }
}
