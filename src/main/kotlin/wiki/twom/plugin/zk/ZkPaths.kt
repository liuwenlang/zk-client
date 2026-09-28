package wiki.twom.plugin.zk

object ZkPaths {
    fun childPath(parent: String, name: String): String =
        if (parent == "/") "/$name" else "$parent/$name"

    fun parentOf(path: String): String? {
        if (path == "/" || !path.startsWith("/")) return null
        val idx = path.lastIndexOf('/')
        return if (idx <= 0) "/" else path.substring(0, idx)
    }

    fun nameOf(path: String): String =
        if (path == "/") "/" else path.substring(path.lastIndexOf('/') + 1)

    fun segments(path: String): List<String> =
        path.split('/').filter { it.isNotEmpty() }

    /** Returns null when the path is well-formed, otherwise an error message. */
    fun validate(path: String): String? = when {
        !path.startsWith("/") -> "Path must start with '/'"
        path.contains("//") -> "Path must not contain empty segments"
        path.endsWith("/") && path != "/" -> "Path must not end with '/'"
        path.contains(" ") -> "Path must not contain spaces"
        else -> null
    }
}
