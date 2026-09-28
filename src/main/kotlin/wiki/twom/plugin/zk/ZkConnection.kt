package wiki.twom.plugin.zk

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import java.util.UUID

class ZkConnectionConfig {
    var id: String = UUID.randomUUID().toString()
    var name: String = ""
    var connectString: String = "localhost:2181"
    var sessionTimeoutMs: Int = ZkConstants.DEFAULT_SESSION_TIMEOUT_MS
    var authUser: String = ""
    var authPassword: String = ""
    var watchEnabled: Boolean = false
    var autoReconnect: Boolean = true

    val displayName: String
        get() = name.ifBlank { connectString }

    val hasAuth: Boolean
        get() = authUser.isNotBlank()
}

/** Host that starts like an IPv4 literal but trails into other characters ("192.168.9.99t"). */
private val NEAR_IP = Regex("""^\d{1,3}(?:\.\d{1,3}){3}[^\d.]""")

/**
 * Returns a human-readable problem with the host list of [connectString], or null.
 * Fake-IP DNS resolvers (proxy TUN modes) happily "resolve" any name, so a typo'd near-IP
 * host connects to the proxy instead of failing with unknown-host, and dies later as an
 * opaque EndOfStreamException — catch the typo up front instead.
 */
fun connectStringHostIssue(connectString: String): String? {
    val hosts = connectString.substringBefore('/').split(',').map { it.substringBefore(':').trim() }
    for (host in hosts) {
        if (NEAR_IP.find(host) != null) return "Host '$host' looks like a mistyped IP address"
    }
    return null
}

@Service(Service.Level.PROJECT)
@State(name = "ZkConnections", storages = [Storage("zk-client.xml")])
class ZkConnectionManager : PersistentStateComponent<ZkConnectionManager> {
    var connections: MutableList<ZkConnectionConfig> = mutableListOf()
    var lastSelectedId: String? = null

    override fun getState(): ZkConnectionManager = this

    override fun loadState(state: ZkConnectionManager) {
        connections = state.connections
        lastSelectedId = state.lastSelectedId
    }

    fun add(config: ZkConnectionConfig) {
        connections.add(config)
    }

    fun update(config: ZkConnectionConfig) {
        val idx = connections.indexOfFirst { it.id == config.id }
        if (idx >= 0) connections[idx] = config
    }

    fun remove(id: String) {
        connections.removeAll { it.id == id }
        if (lastSelectedId == id) lastSelectedId = null
    }

    fun byId(id: String): ZkConnectionConfig? = connections.firstOrNull { it.id == id }

    companion object {
        fun getInstance(project: Project): ZkConnectionManager = project.getService(ZkConnectionManager::class.java)
    }
}
