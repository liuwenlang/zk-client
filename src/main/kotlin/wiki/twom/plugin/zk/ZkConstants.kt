package wiki.twom.plugin.zk

/**
 * Configuration constants for the ZooKeeper client plugin.
 * Centralized to avoid magic numbers scattered across the codebase.
 */
object ZkConstants {
    /** Child lists larger than this are streamed to the UI in batches of this size. */
    const val STREAM_BATCH_SIZE = 2_000

    /** Evict cached child lists below a collapsed node once this many lists are loaded per connection. */
    const val EVICT_LOADED_NODES_THRESHOLD = 20_000

    /** Cap for recursive search results shown in the chooser popup. */
    const val SEARCH_RESULT_LIMIT = 500

    /** Default ZooKeeper session timeout in milliseconds. */
    const val DEFAULT_SESSION_TIMEOUT_MS = 30_000

    /** Minimum allowed session timeout in milliseconds. */
    const val MIN_SESSION_TIMEOUT_MS = 1_000

    /** Maximum allowed session timeout in milliseconds. */
    const val MAX_SESSION_TIMEOUT_MS = 120_000

    /** Connection test timeout in seconds. */
    const val CONNECTION_TEST_TIMEOUT_SECONDS = 8L

    /** Maximum bytes to display in hex preview for binary data. */
    const val HEX_PREVIEW_MAX_BYTES = 4_096

    /** Interval (ms) for polling expanded nodes to detect child changes when watches are enabled. */
    const val AUTO_REFRESH_INTERVAL_MS = 5000L
}
