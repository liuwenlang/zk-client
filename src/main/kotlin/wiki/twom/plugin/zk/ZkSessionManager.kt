package wiki.twom.plugin.zk

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Owns the active [ZkSession]s and dispatches session events to the EDT. */
@Service(Service.Level.PROJECT)
class ZkSessionManager : Disposable {

    private val logger = Logger.getInstance(ZkSessionManager::class.java)

    private val executor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "zk-client-worker").apply { isDaemon = true }
    }

    private val sessions = ConcurrentHashMap<String, ZkSession>()

    /** Set by the tool window panel; replaced on panel rebuild. */
    @Volatile
    var eventHandler: ((ZkSession, ZkSessionEvent) -> Unit)? = null

    fun session(id: String): ZkSession? = sessions[id]

    fun getOrCreate(config: ZkConnectionConfig): ZkSession =
        sessions.computeIfAbsent(config.id) {
            ZkSession(config, executor) { session, event ->
                // any(): events must flow while the add/edit dialog (or any other modal) is open —
                // a default-modality invokeLater would queue them until the last dialog closes,
                // and the tree would look dead despite a live session.
                ApplicationManager.getApplication()
                    .invokeLater({ eventHandler?.invoke(session, event) }, ModalityState.any())
            }
        }

    /** Replaces the session so that config changes (auth, timeout) take effect. */
    fun recreate(config: ZkConnectionConfig): ZkSession {
        remove(config.id)
        return getOrCreate(config)
    }

    fun remove(id: String) {
        sessions.remove(id)?.let { session ->
            executor.execute {
                try {
                    session.disconnect()
                } catch (_: Exception) {
                }
            }
        }
    }

    /** Runs [task] on a worker thread, then [onEdt] on the EDT with its result. */
    fun <T> async(task: () -> T, onEdt: (Result<T>) -> Unit) {
        val application = ApplicationManager.getApplication()
        executor.execute {
            val result = runCatching { task() }
            application.invokeLater({ onEdt(result) }, ModalityState.any())
        }
    }

    override fun dispose() {
        val count = sessions.size
        sessions.values.forEach { session ->
            try {
                session.disconnect()
            } catch (_: Exception) {
            }
        }
        sessions.clear()
        executor.shutdownNow()
        if (count > 0) {
            logger.info("ZkSessionManager disposed, closed $count session(s)")
        }
    }

    companion object {
        fun getInstance(project: Project): ZkSessionManager = project.getService(ZkSessionManager::class.java)
    }
}
