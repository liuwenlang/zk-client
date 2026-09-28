package wiki.twom.plugin.zk

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

object ZkUi {

    private fun group() = NotificationGroupManager.getInstance().getNotificationGroup("ZooKeeper")

    fun notifyInfo(project: Project?, title: String, message: String) {
        group().createNotification(title, message, NotificationType.INFORMATION).notify(project)
    }

    fun notifyError(project: Project?, title: String, error: Throwable) {
        val message = error.message ?: error.toString()
        group().createNotification(title, message, NotificationType.ERROR).notify(project)
    }

    fun notifyWarning(project: Project?, title: String, message: String) {
        group().createNotification(title, message, NotificationType.WARNING).notify(project)
    }
}
