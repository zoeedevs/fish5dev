package com.fish5.features

import com.fish5.Fish5
import com.fish5.clickgui.settings.Setting

/**
 * Base module class: what the click GUI needs: a named, categorised, toggleable
 * feature holding an ordered map of settings. Enable/disable here
 * features are mixin-driven and simply read [enabled] / their settings, so [onEnable]/[onDisable]
 * are plain hooks with no event-bus machinery.
 */
abstract class Module(
    val name: String,
    category: Category? = null,
    @Transient var description: String = "",
    toggled: Boolean = false,
) {

    /** Settings in registration order (a [LinkedHashMap]); keyed by setting name. */
    val settings: LinkedHashMap<String, Setting<*>> = linkedMapOf()

    @Transient
    val category: Category = category ?: Category.RENDER

    var enabled: Boolean = toggled
        private set

    /**
     * Generic "press to toggle this module" key -- the NONE pill on each click-GUI row. Hidden from the
     * settings list (the row pill / settings-page Keybind card edit it). Polled in Fish5's tick loop.
     * Modules with their own "Keybind" setting use that instead;
     * see [rowKeybind].
     */
    val toggleKey: com.fish5.clickgui.settings.impl.KeybindSetting =
        com.fish5.clickgui.settings.impl.KeybindSetting("Toggle Key", com.mojang.blaze3d.platform.InputConstants.UNKNOWN, "Toggles this module when pressed.")
            .also { it.hide(); settings[it.name] = it }

    /** The key the click GUI's row pill shows/edits: the module's own "Keybind" if it has one, else [toggleKey]. */
    val rowKeybind: com.fish5.clickgui.settings.impl.KeybindSetting
        get() = settings["Keybind"] as? com.fish5.clickgui.settings.impl.KeybindSetting ?: toggleKey

    protected inline val mc get() = Fish5.mc

    /**
     * Names this module was saved under before (e.g. modules merged into it). [com.fish5.config.ModuleConfig]
     * loads their settings into this module when no entry under the current name exists yet. The
     * first name's saved enabled state is adopted too.
     */
    open val legacyNames: List<String> get() = emptyList()

    open fun onEnable() {}
    open fun onDisable() {}

    fun toggle() {
        enabled = !enabled
        if (enabled) onEnable() else onDisable()
    }

    fun <K : Setting<*>> registerSetting(setting: K): K {
        settings[setting.name] = setting
        return setting
    }

    operator fun <K : Setting<*>> K.unaryPlus(): K = registerSetting(this)
}
