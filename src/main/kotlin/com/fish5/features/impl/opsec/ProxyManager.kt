package com.fish5.features.impl.opsec

import io.netty.channel.ChannelHandler
import io.netty.handler.proxy.HttpProxyHandler
import io.netty.handler.proxy.Socks5ProxyHandler
import com.fish5.Fish5
import com.fish5.clickgui.settings.Setting.Companion.withDependency
import com.fish5.clickgui.settings.impl.BooleanSetting
import com.fish5.clickgui.settings.impl.ListSetting
import com.fish5.clickgui.settings.impl.SelectorSetting
import com.fish5.clickgui.settings.impl.StringSetting
import com.fish5.features.Category
import com.fish5.features.Module
import com.fish5.notification.NotificationManager
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.Socket

/**
 * Native proxy manager — data + logic backend. The UI is a plain vanilla screen
 * ([com.fish5.clickgui.ProxyScreen]) opened from the Multiplayer menu; this object just holds
 * the persisted state (type, active proxy, presets, enable flag) and builds the Netty
 * handler that [com.fish5.mixin.ConnectionProxyMixin] prepends to the connection pipeline.
 *
 * Proxying is gated by [routeS] + a valid active proxy (not the module enable toggle).
 */
object ProxyManager : Module(
    name = "Proxy Manager",
    category = Category.OPSEC,
    description = "Native proxy routing. Configure it from the Multiplayer menu's Proxy button.",
) {

    private val typeOptions = listOf("SOCKS5", "HTTP")

    /** Delimiter between a preset's name and its proxy display string in [savedProxies]. */
    private const val SEP = ""

    // --- Persisted state (hidden from the in-game panel; the vanilla ProxyScreen edits these) ---
    private val routeS = registerSetting(BooleanSetting("Route through proxy", false, desc = ""))
    private val typeSel = registerSetting(SelectorSetting("Type", "SOCKS5", typeOptions, ""))
    // length must be large — a full "socks5://user:pass@host:port" display easily exceeds the
    // default 32-char cap, and StringSetting silently drops any value longer than its cap (which
    // left the active proxy blank, so Apply never stuck and Save refused with "set a proxy first").
    private val activeS = registerSetting(StringSetting("Active proxy", "", 512, desc = ""))

    /** Presets, persisted as "name0001type://user:pass@host:port". */
    private val savedProxies by ListSetting("Saved proxies", ArrayList<String>())

    private val testExecutor by lazy { com.fish5.utils.DaemonExecutors.single("fish5-proxy-test") }

    /**
     * Optional status sink for the vanilla [ProxyScreen] — that screen has no player, so chat
     * notifications ([NotificationManager], which needs `player`) are silently dropped there. When set,
     * test/save feedback is routed here (on the client thread) instead of chat.
     */
    var statusSink: ((String) -> Unit)? = null

    private fun status(msg: String) {
        val sink = statusSink
        if (sink != null) Fish5.mc.execute { sink(msg) } else NotificationManager.notify(msg)
    }

    init {
        routeS.withDependency { false }
        typeSel.withDependency { false }
        activeS.withDependency { false }
    }

    // ============================ Public API for the vanilla ProxyScreen ============================

    fun proxyTypes(): List<String> = typeOptions

    fun isRouting(): Boolean = routeS.value
    fun setRouting(enabled: Boolean) { routeS.value = enabled }

    fun getType(): String = typeOptions.getOrElse(typeSel.value) { "SOCKS5" }
    fun cycleType() { typeSel.value = (typeSel.value + 1).mod(typeOptions.size) }
    fun setType(name: String) {
        val i = typeOptions.indexOfFirst { it.equals(name, ignoreCase = true) }
        if (i >= 0) typeSel.value = i
    }

    /** Fields (host:port / user / pass) of the active proxy; also syncs the type. Blank when none. */
    fun activeFields(): Fields {
        val e = active() ?: return Fields("", "", "")
        setType(e.type)
        return Fields("${e.host}:${e.port}", e.user ?: "", e.pass ?: "")
    }

    /** Validate + set the active proxy from screen fields. Returns false if unparseable. */
    fun applyFields(hostPort: String, user: String, pass: String): Boolean {
        val e = fieldsToEntry(hostPort, user, pass) ?: return false
        activeS.value = e.display()
        return true
    }

    /** Test the given fields by connecting through the proxy to Hypixel (off-thread). */
    fun testFields(hostPort: String, user: String, pass: String) {
        val e = fieldsToEntry(hostPort, user, pass)
        if (e == null) {
            status("§cInvalid proxy (need host:port).")
            return
        }
        testEntry(e)
    }

    /** Short "host:port (TYPE)" of the active proxy, or null when none is set. Used for the screen header. */
    fun activeSummary(): String? {
        val e = active() ?: return null
        return "${e.host}:${e.port} (${e.type})"
    }

    /** Saved presets as (name, display) pairs, in order. */
    fun presets(): List<Pair<String, String>> = savedProxies.mapNotNull {
        val i = it.indexOf(SEP)
        if (i < 0) null else it.substring(0, i) to it.substring(i + 1)
    }

    /** Save the current active proxy under [name] (auto timestamp name if blank). */
    fun savePreset(name: String) {
        val display = activeS.value
        if (display.isBlank()) {
            status("§cSet a proxy first (Apply), then Save.")
            return
        }
        val nm = name.ifBlank { "proxy-" + (System.currentTimeMillis() % 100000) }
        savedProxies.removeAll { it.startsWith("$nm$SEP") }
        savedProxies.add("$nm$SEP$display")
        status("§aSaved preset '$nm'.")
    }

    fun deletePreset(name: String) {
        savedProxies.removeAll { it.startsWith("$name$SEP") }
    }

    /** Make a preset's proxy the active one (and sync type). */
    fun loadPreset(display: String) {
        val e = ProxyEntry.parse(display, getType()) ?: return
        setType(e.type)
        activeS.value = e.display()
    }

    data class Fields(val hostPort: String, val user: String, val pass: String)

    private fun fieldsToEntry(hostPort: String, user: String, pass: String): ProxyEntry? {
        val raw = if (user.isBlank()) hostPort.trim() else "${user.trim()}:$pass@${hostPort.trim()}"
        return ProxyEntry.parse(raw, getType())
    }

    // ============================ Netty handler (ConnectionProxyMixin) ============================

    /** The proxy handler to prepend to the channel pipeline, or null when proxying is off/suspended. */
    fun createHandler(): ChannelHandler? {
        if (!routeS.value) return null
        val e = active() ?: return null
        val addr = InetSocketAddress(e.host, e.port)
        return if (e.isSocks) {
            if (!e.user.isNullOrEmpty()) Socks5ProxyHandler(addr, e.user, e.pass ?: "") else Socks5ProxyHandler(addr)
        } else {
            if (!e.user.isNullOrEmpty()) HttpProxyHandler(addr, e.user, e.pass ?: "") else HttpProxyHandler(addr)
        }
    }

    private fun active(): ProxyEntry? = ProxyEntry.parse(activeS.value, typeOptions[typeSel.value])

    private fun testEntry(e: ProxyEntry) {
        status("§7Testing ${e.redacted()} …")
        testExecutor.execute {
            if (!e.user.isNullOrEmpty()) {
                Authenticator.setDefault(object : Authenticator() {
                    override fun getPasswordAuthentication() = PasswordAuthentication(e.user, (e.pass ?: "").toCharArray())
                })
            }
            val proxy = Proxy(if (e.isSocks) Proxy.Type.SOCKS else Proxy.Type.HTTP, InetSocketAddress(e.host, e.port))
            try {
                Socket(proxy).use { sock ->
                    val t0 = System.currentTimeMillis()
                    sock.connect(InetSocketAddress("mc.hypixel.net", 25565), 8000)
                    status("§aProxy OK §7(${System.currentTimeMillis() - t0}ms)")
                }
            } catch (ex: Exception) {
                status("§cProxy failed: §7${ex.message}")
            }
        }
    }

    private data class ProxyEntry(
        val type: String,
        val host: String,
        val port: Int,
        val user: String?,
        val pass: String?,
    ) {
        val isSocks get() = type.equals("SOCKS5", ignoreCase = true) || type.equals("SOCKS", ignoreCase = true)

        fun display(): String {
            val creds = if (!user.isNullOrEmpty()) "$user:${pass ?: ""}@" else ""
            return "${type.lowercase()}://$creds$host:$port"
        }

        fun redacted(): String {
            val creds = if (!user.isNullOrEmpty()) "$user:***@" else ""
            return "${type.lowercase()}://$creds$host:$port"
        }

        companion object {
            fun parse(raw: String, defaultType: String): ProxyEntry? {
                var s = raw.trim()
                if (s.isEmpty()) return null

                var type = defaultType
                val scheme = s.indexOf("://")
                if (scheme >= 0) {
                    type = if (s.substring(0, scheme).uppercase().startsWith("SOCKS")) "SOCKS5" else "HTTP"
                    s = s.substring(scheme + 3)
                }

                var user: String? = null
                var pass: String? = null
                val at = s.lastIndexOf('@')
                if (at >= 0) {
                    val creds = s.substring(0, at)
                    s = s.substring(at + 1)
                    val ci = creds.indexOf(':')
                    if (ci >= 0) {
                        user = creds.substring(0, ci)
                        pass = creds.substring(ci + 1)
                    } else {
                        user = creds
                    }
                }

                val parts = s.split(':')
                return when (parts.size) {
                    2 -> parts[1].trim().toIntOrNull()?.let { ProxyEntry(type, parts[0].trim(), it, user, pass) }
                    4 -> parts[1].trim().toIntOrNull()?.let { ProxyEntry(type, parts[0].trim(), it, parts[2], parts[3]) }
                    else -> null
                }
            }
        }
    }
}
