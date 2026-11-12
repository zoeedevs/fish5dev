package com.fish5.features.impl.render

import com.mojang.blaze3d.platform.InputConstants
import com.fish5.clickgui.settings.impl.BooleanSetting
import com.fish5.clickgui.settings.impl.KeybindSetting
import com.fish5.clickgui.settings.impl.KeybindSetting.Companion.isDown
import com.fish5.clickgui.settings.impl.NumberSetting
import com.fish5.clickgui.settings.impl.SelectorSetting
import com.fish5.features.Category
import com.fish5.features.Module
import net.minecraft.client.gui.screens.ChatScreen

/**
 * FreeLook — decouples the camera from the player's body rotation. Ported from Jasper Client's
 * FreeLook: this module is the Odin-style settings container + on/off switch, and the camera logic
 * lives in [FreeLook]. Every original Jasper setting is preserved here; the custom aurora HUD pill and
 * third-person avatar polish (Item Chams/Glow/Animations) were dropped — see [FreecamModule]'s doc for
 * why.
 */
object FreelookModule : Module(
    name = "Freelook",
    category = Category.RENDER,
    description = "Decouple the camera from your body. Camera mode: mouse swings the camera. " +
        "Player mode: mouse turns your body and the camera auto-orbits. Can't be active at the " +
        "same time as Freecam.",
) {
    private val sKey = KeybindSetting(
        "Keybind", InputConstants.UNKNOWN,
        desc = "Toggle Freelook on/off (unbound by default).",
    ).also { it.hide() } // edited from the row pill, not the settings page
    private val sMode = SelectorSetting(
        "Mode", "Camera", listOf("Camera", "Player"),
        desc = "Camera: mouse swings a decoupled camera. Player: mouse turns your body as normal " +
            "and the camera auto-orbits instead.",
    )
    private val sTogglePerspective = BooleanSetting(
        "Toggle perspective", true,
        desc = "Force third-person while active so the decoupled camera/orbit is visible; " +
            "restores your perspective on disable.",
    )
    private val sThroughBlocks = BooleanSetting(
        "Through blocks", false,
        desc = "The camera clips through terrain instead of stopping at a wall behind you.",
    )
    private val sSensitivity = NumberSetting<Double>(
        "Camera sensitivity", 8.0, 0.1, 10.0, 0.5,
        desc = "Divisor against the raw mouse delta in Camera mode — higher = slower turn. " +
            "Unused in Player mode.",
    )

    init {
        +sKey; +sMode; +sTogglePerspective; +sThroughBlocks; +sSensitivity
    }

    private var keyWasDown = false

    // Minecraft.screen was a direct field through 26.1.2; 26.2 moved screen ownership onto Gui
    // (mc.gui.screen()), same split GuiKeepCrosshairMixin already branches on.
    private fun currentScreen() =
        mc.gui.screen()

    override fun onEnable() {
        val player = mc.player
        if (player == null) {
            toggle() // bounce back off — nothing to look around with
            return
        }
        if (FreecamModule.enabled) FreecamModule.toggle() // mutually exclusive
        FreeLook.activate(player.yRot, player.xRot)
    }

    override fun onDisable() = FreeLook.deactivate()

    /** Called every client tick (from [com.fish5.Fish5]'s tick loop); drives the keybind + orbit. */
    fun tick() {
        val down = sKey.value != InputConstants.UNKNOWN && sKey.value.isDown()
        if (down && !keyWasDown && currentScreen() !is ChatScreen) {
            keyWasDown = true
            toggle()
        } else if (!down) {
            keyWasDown = false
        }
        if (enabled) FreeLook.tick(currentScreen() == null && mc.player != null)
    }

    /** Mode 0 = Camera (mouse swings the camera); 1 = Player (mouse turns the body, camera auto-orbits). */
    @JvmStatic fun isCameraMode(): Boolean = sMode.value != 1
    @JvmStatic fun togglePerspective(): Boolean = sTogglePerspective.enabled
    @JvmStatic fun throughBlocks(): Boolean = sThroughBlocks.enabled
    @JvmStatic fun cameraSensitivity(): Double = sSensitivity.value
}
