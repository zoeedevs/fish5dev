package com.fish5.features.impl.render

import com.mojang.blaze3d.platform.InputConstants
import com.fish5.clickgui.settings.impl.BooleanSetting
import com.fish5.clickgui.settings.impl.KeybindSetting
import com.fish5.clickgui.settings.impl.KeybindSetting.Companion.isDown
import com.fish5.clickgui.settings.impl.NumberSetting
import com.fish5.features.Category
import com.fish5.features.Module
import net.minecraft.client.gui.screens.ChatScreen

/**
 * FreeCam — a client-side-only camera that flies freely through blocks while your body stays behind
 * (held keys just release, so momentum decays naturally instead of snapping to a stop). Ported from
 * Jasper Client's FreeCam: this module is the Odin-style settings container + on/off switch, and the
 * flight/camera logic lives in [FreeCam]. Every original Jasper setting is preserved here; the custom
 * aurora HUD pill and third-person avatar polish (Item Chams/Glow/Animations) were dropped — fish5
 * has no chams/glow/animation systems for those to hook into, and vanilla already renders your own
 * body fine in third person.
 */
object FreecamModule : Module(
    name = "Freecam",
    category = Category.RENDER,
    description = "Fly the camera freely through blocks while your body stays behind. Can't be " +
        "active at the same time as Freelook.",
) {
    private val sKey = KeybindSetting(
        "Keybind", InputConstants.UNKNOWN,
        desc = "Toggle Freecam on/off (unbound by default).",
    ).also { it.hide() } // edited from the row pill, not the settings page
    private val sSpeed = NumberSetting<Double>(
        "Speed", 1.0, 0.1, 50.0, 0.1,
        desc = "Flat (yaw-only) fly speed, blocks/tick.",
    )
    private val sVerticalSpeed = NumberSetting<Double>(
        "Vertical speed", 1.0, 0.2, 5.0, 0.1,
        desc = "Multiplier on Speed for the Jump/Sneak vertical fly.",
    )
    private val sSmooth = BooleanSetting(
        "Smooth", true,
        desc = "Halves the per-tick step for smoother flight.",
    )
    private val sShowBody = BooleanSetting(
        "Show body", true,
        desc = "Force third-person so your own body is visible while detached.",
    )
    private val sShowHands = BooleanSetting(
        "Show hands", true,
        desc = "Keep rendering the held item even while Show Body forces third-person.",
    )
    private val sScrollSpeed = BooleanSetting(
        "Scroll speed", true,
        desc = "Mouse wheel scales fly speed while active.",
    )
    private val sScrollResets = BooleanSetting(
        "Scroll resets", true,
        desc = "Scroll's speed change is transient and resets next activation, instead of " +
            "permanently overwriting Speed.",
    )

    init {
        +sKey; +sSpeed; +sVerticalSpeed; +sSmooth; +sShowBody; +sShowHands; +sScrollSpeed; +sScrollResets
    }

    private var keyWasDown = false

    // Minecraft.screen was a direct field through 26.1.2; 26.2 moved screen ownership onto Gui
    // (mc.gui.screen()), same split GuiKeepCrosshairMixin already branches on.
    private fun currentScreen() =
        mc.gui.screen()

    override fun onEnable() {
        val player = mc.player
        if (player == null) {
            // Can't actually start flying — bounce the toggle back off so the GUI/keybind stay honest.
            toggle()
            return
        }
        if (FreelookModule.enabled) FreelookModule.toggle() // mutually exclusive
        FreeCam.activate(player.x, player.eyeY, player.z, player.yRot, player.xRot)
    }

    override fun onDisable() = FreeCam.deactivate()

    /** Called every client tick (from [com.fish5.Fish5]'s tick loop); drives the keybind + flight. */
    fun tick() {
        val down = sKey.value != InputConstants.UNKNOWN && sKey.value.isDown()
        if (down && !keyWasDown && currentScreen() !is ChatScreen) {
            keyWasDown = true
            toggle()
        } else if (!down) {
            keyWasDown = false
        }
        if (enabled) FreeCam.tick(mc)
    }

    // ── Java-facing accessors (read by FreeCam, mirrors Jasper's ConfigManager fields) ──
    @JvmStatic fun speed(): Double = sSpeed.value
    @JvmStatic fun setSpeed(v: Float) { sSpeed.value = v.toDouble() }
    @JvmStatic fun verticalSpeed(): Double = sVerticalSpeed.value
    @JvmStatic fun smooth(): Boolean = sSmooth.enabled
    @JvmStatic fun showBody(): Boolean = sShowBody.enabled
    @JvmStatic fun showHands(): Boolean = sShowHands.enabled
    @JvmStatic fun scrollSpeedEnabled(): Boolean = sScrollSpeed.enabled
    @JvmStatic fun scrollResets(): Boolean = sScrollResets.enabled
}
