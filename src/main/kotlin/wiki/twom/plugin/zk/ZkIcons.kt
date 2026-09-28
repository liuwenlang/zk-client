package wiki.twom.plugin.zk

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * Plugin-specific SVG icons (database-cylinder + status overlay, in the spirit of the
 * IDEA Database tool window). Colors are mid-tones that read on both light and dark themes.
 */
object ZkIcons {
    @JvmField val connected: Icon = load("icons/zkConnected.svg")
    @JvmField val connecting: Icon = load("icons/zkConnecting.svg")
    @JvmField val disconnected: Icon = load("icons/zkDisconnected.svg")
    @JvmField val expired: Icon = load("icons/zkExpired.svg")
    @JvmField val authFailed: Icon = load("icons/zkAuthFailed.svg")

    @JvmField val watch: Icon = load("icons/zkWatch.svg")
    @JvmField val watchOff: Icon = load("icons/zkWatchOff.svg")

    @JvmField val connect: Icon = load("icons/zkConnect.svg")
    @JvmField val disconnect: Icon = load("icons/zkDisconnect.svg")

    /** Crosshair/locate glyph — "Go to Path" (Search keeps the standard magnifier). */
    @JvmField val goTo: Icon = load("icons/zkGoTo.svg")

    private fun load(path: String): Icon = IconLoader.getIcon(path, ZkIcons::class.java)
}
