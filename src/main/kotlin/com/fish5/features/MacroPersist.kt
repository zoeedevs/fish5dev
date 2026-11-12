package com.fish5.features

import com.fish5.Fish5
import com.fish5.automation.Msg
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.gizmos.Gizmos
import net.minecraft.gizmos.TextGizmo
import net.minecraft.util.ARGB
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import org.lwjgl.glfw.GLFW

/**
 * Keeps a user-paused macro ([Macro.userStop]) resumable for [MacroSettings.resumeWindowSec], while the
 * player stays inside a square of [MacroSettings.resumeRadius] around where they paused. The square is
 * drawn on the ground (green in the middle, red near the edge) with the time left floating above it.
 *
 * The pause ends for good (the macro's normal stop) when the time runs out, the player walks out of the
 * square, the world changes, or Esc is pressed twice within [ESC_WINDOW_MS]. Esc only counts when no
 * menu was open (the pause menu Esc itself opens is fine), so closing a chest with Esc doesn't.
 */
object MacroPersist {

    private const val ESC_WINDOW_MS = 2000L

    private var macro: Macro? = null
    private var cx = 0.0
    private var cy = 0.0
    private var cz = 0.0
    private var until = 0L
    private var level: Level? = null
    // no timer, no square, survives world changes (auto mode's Forge trip)
    private var indefinite = false

    private var escWasDown = false
    private var lastEscAt = 0L
    private var prevScreen: Screen? = null

    fun beginIndefinite(m: Macro) {
        val p = Fish5.mc.player
        begin(m, p?.x ?: 0.0, p?.y ?: 0.0, p?.z ?: 0.0)
        indefinite = true
    }

    /** `/f5 end`: end a paused macro for good (or stop a running one). */
    fun endCommand() {
        if (macro != null) { end("/f5 end"); return }
        val running = MacroManager.running
        if (running != null) running.stop() else Msg.chat("§7No macro running or paused.")
    }

    fun begin(m: Macro, x: Double, y: Double, z: Double) {
        indefinite = false
        macro = m
        cx = x; cy = y; cz = z
        until = System.currentTimeMillis() + MacroSettings.resumeWindowSec * 1000L
        level = Fish5.mc.level
        lastEscAt = 0L
        escWasDown = true  // a held key from before doesn't count as a press
        prevScreen = Fish5.mc.gui.screen()
    }

    fun clear(m: Macro) {
        if (macro === m) macro = null
    }

    private fun end(why: String) {
        val m = macro ?: return
        macro = null
        Msg.chat("§7${m.name} pause ended §8($why)§7, stopped.")
        m.stop()
    }

    /** Client tick (from Fish5). Nothing to do unless a macro is paused. */
    fun tick() {
        if (macro == null) return
        val mc = Fish5.mc
        val p = mc.player
        val now = System.currentTimeMillis()
        if (!indefinite) {
            if (p == null || mc.level == null || mc.level !== level) { end("left the world"); return }
            if (now >= until) { end("time's up"); return }
            if (distanceToCentre(p.x, p.z) > MacroSettings.resumeRadius) { end("walked out of the square"); return }
        } else if (mc.connection == null) {
            end("disconnected")
            return
        }
        if (p == null) return

        val screen = mc.gui.screen()
        val escDown = InputConstants.isKeyDown(mc.window, GLFW.GLFW_KEY_ESCAPE)
        if (escDown && !escWasDown && (prevScreen == null || prevScreen is PauseScreen)) {
            if (lastEscAt != 0L && now - lastEscAt <= ESC_WINDOW_MS) {
                if (screen is PauseScreen) mc.gui.setScreen(null)  // the second Esc reopened the pause menu
                end("Esc Esc")
                return
            }
            lastEscAt = now
        }
        escWasDown = escDown
        prevScreen = screen
    }

    // the square is axis-aligned, so "how far in" is the larger of the two horizontal offsets
    private fun distanceToCentre(x: Double, z: Double) = maxOf(Math.abs(x - cx), Math.abs(z - cz))

    private val textStyle = TextGizmo.Style.whiteAndCentered().withScale(0.6f)

    /** Once per rendered frame (LevelRendererEspMixin): the square and the timer, only while paused. */
    @JvmStatic
    fun emit() {
        val m = macro ?: return
        if (indefinite) return
        val p = Fish5.mc.player ?: return
        val r = MacroSettings.resumeRadius
        // green at the centre, red at the edge
        val t = (distanceToCentre(p.x, p.z) / r).coerceIn(0.0, 1.0).toFloat()
        val color = ARGB.color(255, (80 + 175 * t).toInt(), (220 - 160 * t).toInt(), 90)
        val y = Math.floor(cy) + 0.02
        val col = Fish5.mc.levelRenderer.collectPerFrameRenderThreadGizmos()
        try {
            val a = Vec3(cx - r, y, cz - r); val b = Vec3(cx + r, y, cz - r)
            val c = Vec3(cx + r, y, cz + r); val d = Vec3(cx - r, y, cz + r)
            Gizmos.line(a, b, color, 3f); Gizmos.line(b, c, color, 3f)
            Gizmos.line(c, d, color, 3f); Gizmos.line(d, a, color, 3f)
            val left = ((until - System.currentTimeMillis()).coerceAtLeast(0L) + 999) / 1000
            val edge = r - distanceToCentre(p.x, p.z)
            Gizmos.billboardText("${m.name} paused: ${left}s", Vec3(cx, y + 2.6, cz), textStyle)
            Gizmos.billboardText(String.format(java.util.Locale.US, "%.1f blocks to the edge", edge.coerceAtLeast(0.0)),
                Vec3(cx, y + 2.3, cz), TextGizmo.Style.forColorAndCentered(color).withScale(0.45f))
        } finally {
            col.close()
        }
    }
}
