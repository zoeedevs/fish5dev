// Ported from aether (https://github.com/mizly/aether), GPL-3.0-or-later.
// Modified for fish5; see com/fish5/PORTED.md.
package com.fish5.features.impl.misc

import com.fish5.clickgui.settings.impl.BooleanSetting
import com.fish5.features.Category
import com.fish5.features.Module
import com.fish5.features.impl.render.FreeCam
import com.fish5.features.impl.render.FreeLook
import com.fish5.macro.InputLock
import com.fish5.macro.MacroStateManager
import com.fish5.utils.ClientUtils

/**
 * Releases the cursor (by default only while a macro runs) so you can use other windows without the
 * game re-grabbing it. [com.fish5.mixin.MouseHandlerMixin] reads [isMouseUngrabbed] to block vanilla's
 * re-grab and in-game clicks/scrolls while released. Freecam and Freelook need the mouse, so the cursor
 * is grabbed again while either is active and released again after.
 *
 * Synced once per tick from [com.fish5.Fish5]; only flips the grab when the wanted state changes.
 */
object UngrabMouse : Module(
    name = "Ungrab Mouse",
    category = Category.MODULES,
    description = "Release the cursor while a macro runs, so you can use other windows. Freecam/Freelook take it back while active.",
) {
    private val onlyWhileMacro by BooleanSetting(
        "Only while macro runs", true,
        desc = "Off: keep the cursor released whenever you're in a world with no screen open.",
    )

    @Volatile
    private var ungrabbed = false

    /** True while the cursor is deliberately released: vanilla must not re-grab it or act on clicks. */
    @JvmStatic
    fun isMouseUngrabbed(): Boolean =
        ungrabbed && mc.player != null && mc.level != null && mc.gui.screen() == null

    override fun onDisable() = tick()

    fun tick() {
        // the macro input lock always releases the cursor, whether or not this module is on
        val want = ((enabled && (!onlyWhileMacro || MacroStateManager.isMacroRunning())) || InputLock.isActive()) &&
            !FreeCam.isActive() && !FreeLook.isActive() &&
            mc.player != null && mc.level != null
        if (want == ungrabbed) return
        ungrabbed = want
        val mouse = mc.mouseHandler
        if (want) {
            if (mouse.isMouseGrabbed) mouse.releaseMouse()
        } else if (!mouse.isMouseGrabbed && mc.gui.screen() == null && mc.isWindowActive) {
            mouse.grabMouse()
            // grabMouse() resets key states to the physical keys; put the macro's held keys back.
            ClientUtils.reapplyProgrammaticKeyStates(mc)
        }
    }
}
