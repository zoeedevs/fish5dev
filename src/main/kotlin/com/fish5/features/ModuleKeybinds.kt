package com.fish5.features

import com.mojang.blaze3d.platform.InputConstants
import com.fish5.Fish5.mc
import com.fish5.clickgui.ClickGUI
import com.fish5.clickgui.settings.impl.KeybindSetting.Companion.isDown
import com.fish5.features.impl.render.ClickGUIModule

/**
 * Polled every client tick: the GUI Keybind (opens the click GUI), the General Macro Toggle (starts /
 * stops the most recent macro), and every non-macro module's generic [Module.toggleKey] (the key pill on
 * its click-GUI row). Edge-triggered, and only while no screen is open so typing in chat / menus never
 * toggles anything. Macros have no keys of their own.
 */
object ModuleKeybinds {

    private val wasDown = HashMap<Any, Boolean>()

    private fun pressed(id: Any, key: InputConstants.Key): Boolean {
        val down = key != InputConstants.UNKNOWN && key.isDown()
        val before = wasDown.put(id, down) ?: false
        return down && !before
    }

    fun tick() {
        val screenOpen = mc.gui.screen() != null

        if (pressed("gui", ClickGUIModule.guiKey) && !screenOpen) {
            mc.gui.setScreen(ClickGUI)
            return
        }
        if (pressed("macro", MacroSettings.generalToggle.value) && !screenOpen) {
            MacroManager.toggleRecent()
            return
        }
        for (m in ModuleManager.modules.values) {
            if (m is Macro || m === MacroSettings) continue
            val key = m.toggleKey.value
            if (key == InputConstants.UNKNOWN) { wasDown.remove(m); continue }
            if (pressed(m, key) && !screenOpen) m.toggle()
        }
    }
}
