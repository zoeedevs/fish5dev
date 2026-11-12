package com.fish5.features

import com.fish5.automation.Msg
import com.fish5.clickgui.settings.impl.KeybindSetting
import com.fish5.clickgui.settings.impl.MapSetting
import com.fish5.clickgui.settings.impl.StringSetting
import com.fish5.macro.ClassPreloader
import com.fish5.macro.MacroStateManager
import com.fish5.notification.NotificationManager
import com.mojang.blaze3d.platform.InputConstants
import java.time.LocalDate

/**
 * A macro: a [Module] that is started and stopped instead of toggled. Macros have no enable switch and
 * no keybind of their own. The click GUI gives each one a page (overview, instructions, activity,
 * settings, START), and the single [MacroSettings.generalToggle] key starts/stops the most recent one.
 *
 * Running state is [enabled], but it is never restored from config (see [com.fish5.config.ModuleConfig]),
 * so a macro can't start by itself when the game launches.
 */
abstract class Macro(
    name: String,
    /** Area tab it is listed under on the Macros page, e.g. "Crystal Hollows". */
    val area: String,
    description: String,
    val status: Status = Status.WORKING,
    /** Text for the Instructions tab of the macro page. */
    val instructions: String = "",
) : Module(name, Category.MODULES, description) {

    /** The status dot next to the name. */
    enum class Status(val label: String) { WORKING("Working"), TESTING("In progress"), BROKEN("Broken") }

    // Persisted usage stats: "total" (ms), "sessions", and "d<epochDay>" (ms that day, last 30 days only).
    private val stats = registerSetting(MapSetting("Stats", hashMapOf<String, Long>())).value
    private var runStartedAt = 0L

    /**
     * Paused by the user (see [userStop]): still enabled, so nothing of its state is lost, but not
     * running and the failsafes don't watch. START resumes it until [MacroPersist] gives up on it.
     */
    var suspended = false
        private set

    val running: Boolean get() = enabled && !suspended

    private val stopListener = Runnable { stop() }

    /** True while the first start of this session is preloading the macro's classes. */
    var loading = false
        private set
    private var preloaded = false
    private var loadCancelled = false

    /**
     * Starts this macro (stopping any other one first). Needs to be in a world. The first start of a game
     * session preloads every class the macro can reach (see [ClassPreloader]) before the start message, so a
     * jar replaced on disk can't crash it mid-run.
     */
    fun start() {
        if (suspended) { resume(); return }
        if (running || loading) return
        if (mc.player == null || mc.level == null) {
            NotificationManager.notify("§cJoin a world before starting $name.")
            return
        }
        for (m in MacroManager.macros) if (m !== this) m.stop()
        MacroSettings.lastMacro = name
        if (preloaded) {
            toggle()
            return
        }
        loading = true
        loadCancelled = false
        Msg.chat("§7Loading §f$name§7...")
        try {
            ClassPreloader.preloadAsync(javaClass) { result -> finishLoading(result) }
        } catch (t: Throwable) {
            // the preloader itself couldn't be used: same cause, same answer
            finishLoading(ClassPreloader.Result(0, 0, t.javaClass.simpleName))
        }
    }

    private fun finishLoading(result: ClassPreloader.Result) {
        loading = false
        if (result.error != null) {
            Msg.chat("§cCouldn't load §f$name §c(${result.error}). The fish5 jar changed since launch: restart Minecraft.")
            NotificationManager.notify("§cRestart Minecraft to use the new fish5 jar.")
            return
        }
        preloaded = true
        if (loadCancelled || mc.player == null || mc.level == null) return
        toggle()
    }

    /** Stops for good (failsafes, another macro starting, the pause running out). */
    fun stop() {
        if (loading) loadCancelled = true
        if (suspended) {
            suspended = false
            MacroPersist.clear(this)
            toggle()
            return
        }
        if (running) toggle()
    }

    /**
     * The user stopped it (key or button). With [MacroSettings.persistent] on, it only pauses: keys
     * released and failsafes off, but every bit of state kept, so START within the window carries on
     * exactly where it left off. [MacroPersist] ends the pause (timer, walking off, Esc Esc).
     */
    fun userStop() {
        if (loading || !running || !MacroSettings.persistent) { stop(); return }
        val p = mc.player
        if (p == null) { stop(); return }
        suspend()
        MacroPersist.begin(this, p.x, p.y, p.z)
        Msg.chat("§ePaused §f$name§e. §7START again within §f${MacroSettings.resumeWindowSec}s §7to carry on, " +
            "stay inside the square. §8(Esc twice to end it now)")
    }

    /**
     * Pause with no timer and no square, for when the macro itself had to leave (auto mode's Forge trip):
     * the session waits until the user is set up again and presses START. Only Esc Esc, `/f5 end` or
     * closing the game end it.
     */
    fun suspendIndefinitely(why: String) {
        if (!running) return
        suspend()
        MacroPersist.beginIndefinite(this)
        Msg.chat("§ePaused §f$name§e: $why §7Press START when you're set up. §8(Esc twice or /f5 end to end it)")
    }

    private fun suspend() {
        suspended = true
        // our own stop listener would turn this pause into a full stop
        MacroStateManager.removeStopListener(stopListener)
        MacroStateManager.stopMacro(mc, "$name paused", false)
        onSuspend()
    }

    private fun resume() {
        if (mc.player == null || mc.level == null) return
        for (m in MacroManager.macros) if (m !== this) m.stop()
        suspended = false
        MacroPersist.clear(this)
        MacroSettings.lastMacro = name
        MacroStateManager.addStopListener(stopListener)
        MacroStateManager.startMacro(mc)
        Msg.chat("§aResumed §f$name")
        onResume()
    }

    final override fun onEnable() {
        runStartedAt = System.currentTimeMillis()
        stats["sessions"] = (stats["sessions"] ?: 0L) + 1L
        MacroStateManager.addStopListener(stopListener)
        MacroStateManager.startMacro(mc)
        MacroSession.begin(this)
        Msg.chat("§aStarted §f$name §7($area)")
        onStart()
    }

    final override fun onDisable() {
        recordRun()
        onStop()
        MacroSession.end(this)
        MacroStateManager.removeStopListener(stopListener)
        // A failsafe stop has already set the state to OFF; a manual stop still has to.
        if (MacroStateManager.isMacroRunning()) MacroStateManager.stopMacro(mc, "$name stopped", false)
    }

    /** Called once the macro is marked running and the failsafes are watching. */
    protected open fun onStart() {}

    /** Called when the macro stops for good (failsafe, disconnect, a pause that ran out). */
    protected open fun onStop() {}

    /** The user paused it: let go of anything held, keep all state. Keys/rotation are already released. */
    protected open fun onSuspend() {}

    /** Started again after a pause: carry on from the kept state. The failsafes are watching again. */
    protected open fun onResume() {}

    // -- Macro HUD ---------------------------------------------------------------------------------

    /** What the macro is doing right now, in a few words ("Waiting for bite"). Read a few times a second. */
    open val hudStatus: String get() = "Running"

    private fun recordRun() {
        if (runStartedAt == 0L) return
        val ms = (System.currentTimeMillis() - runStartedAt).coerceAtLeast(0L)
        runStartedAt = 0L
        stats["total"] = (stats["total"] ?: 0L) + ms
        val today = LocalDate.now().toEpochDay()
        val key = "d$today"
        stats[key] = (stats[key] ?: 0L) + ms
        // keep only the last 30 days
        val it = stats.keys.iterator()
        while (it.hasNext()) {
            val k = it.next()
            if (k.startsWith("d") && (k.substring(1).toLongOrNull() ?: 0L) <= today - ACTIVITY_DAYS) it.remove()
        }
    }

    private fun liveMs() = if (running && runStartedAt != 0L) (System.currentTimeMillis() - runStartedAt).coerceAtLeast(0L) else 0L

    /** Total run time in ms, including the current run. */
    val totalTimeMs: Long get() = (stats["total"] ?: 0L) + liveMs()

    val sessions: Long get() = stats["sessions"] ?: 0L

    /** Run time in ms on the day [daysAgo] days before today (0 = today, including the current run). */
    fun activityMs(daysAgo: Int): Long {
        val day = LocalDate.now().toEpochDay() - daysAgo
        return (stats["d$day"] ?: 0L) + if (daysAgo == 0) liveMs() else 0L
    }

    companion object {
        const val ACTIVITY_DAYS = 30
    }
}

/** Every registered [Macro], plus the "most recent macro" toggle. */
object MacroManager {

    val macros: List<Macro> by lazy { ModuleManager.modules.values.filterIsInstance<Macro>() }

    val running: Macro? get() = macros.firstOrNull { it.running }

    /** Stops the running macro, or starts the most recently started one (the first macro if none yet). */
    fun toggleRecent() {
        running?.let { it.userStop(); return }
        macros.firstOrNull { it.loading }?.let { it.stop(); return }
        macros.firstOrNull { it.suspended }?.let { it.start(); return }
        val last = macros.firstOrNull { it.name == MacroSettings.lastMacro } ?: macros.firstOrNull() ?: return
        last.start()
    }
}

/** Settings shared by all macros: the General Macro Toggle key on the Macros page. */
object MacroSettings : Module("Macros", Category.MODULES, "Settings shared by every macro.") {

    val generalToggle = registerSetting(KeybindSetting("General Macro Toggle", InputConstants.UNKNOWN, "Toggle most recent"))

    private val persistentSetting = registerSetting(com.fish5.clickgui.settings.impl.BooleanSetting(
        "Pause instead of stop", true,
        desc = "When you stop a macro it pauses instead: START again within the window, inside the square on the " +
            "ground, and it carries on where it left off. Press Esc twice to end it right away.",
    ))
    private val resumeWindowSetting = registerSetting(com.fish5.clickgui.settings.impl.NumberSetting<Int>(
        "Pause window", 30, 10, 120, 5, unit = " s", desc = "How long a paused macro can be resumed.",
    ))
    private val resumeRadiusSetting = registerSetting(com.fish5.clickgui.settings.impl.NumberSetting<Double>(
        "Pause zone", 4.0, 2.0, 16.0, 1.0, unit = " blocks", desc = "Walk further than this from where you paused and the pause ends.",
    ))

    val persistent get() = persistentSetting.value
    val resumeWindowSec get() = resumeWindowSetting.value
    val resumeRadius get() = resumeRadiusSetting.value

    /** The settings on Macros > General. */
    fun generalSettings(): List<com.fish5.clickgui.settings.RenderableSetting<*>> =
        listOf(generalToggle, persistentSetting, resumeWindowSetting, resumeRadiusSetting)

    private val lastMacroSetting = StringSetting("Last Macro", "", 64, desc = "").also { it.hide(); registerSetting(it) }

    var lastMacro: String
        get() = lastMacroSetting.value
        set(v) { lastMacroSetting.value = v }
}
