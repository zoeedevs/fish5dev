package com.fish5.features

import com.fish5.Fish5
import com.fish5.config.ModuleConfig
import com.fish5.features.impl.failsafe.FailsafeSettings
import com.fish5.features.impl.failsafe.GuiOpenedFailsafeModule
import com.fish5.features.impl.failsafe.InventorySlotFailsafeModule
import com.fish5.features.impl.failsafe.RotationFailsafeModule
import com.fish5.features.impl.failsafe.WorldChangeFailsafeModule
import com.fish5.features.impl.fishing.Fishing
import com.fish5.features.impl.fishing.WormFishing
import com.fish5.features.impl.fishing.PartySeaCreatures
import com.fish5.features.impl.failsafe.BreakSettings
import com.fish5.features.impl.misc.ExploitPreventer
import com.fish5.features.impl.misc.UngrabMouse
import com.fish5.features.impl.opsec.ProxyManager
import com.fish5.features.impl.render.ClickGUIModule
import com.fish5.features.impl.render.CustomSwingSpeed
import com.fish5.features.impl.render.FreecamModule
import com.fish5.features.impl.render.FreelookModule
import com.fish5.features.impl.render.Fullbright
import com.fish5.features.impl.render.HeldItem
import com.fish5.features.impl.render.PlayerEsp
import com.fish5.features.impl.render.PlayerSize
import com.fish5.features.impl.skyblock.WormHelper
import java.io.File

/**
 * Holds all modules (by name and by category) and persists them via [ModuleConfig].
 */
object ModuleManager {

    val modules: LinkedHashMap<String, Module> = linkedMapOf()
    val modulesByCategory: HashMap<Category, ArrayList<Module>> = hashMapOf()
    val configs: ArrayList<ModuleConfig> = arrayListOf()

    init {
        registerModules(
            ModuleConfig(File(Fish5.configDir, "modules.json")),
            ClickGUIModule,
            WormHelper,
            Fishing,
            MacroSettings,
            WormFishing,
            FailsafeSettings,
            RotationFailsafeModule,
            InventorySlotFailsafeModule,
            GuiOpenedFailsafeModule,
            WorldChangeFailsafeModule,
            BreakSettings,
            PartySeaCreatures,
            com.fish5.hud.MacroHud,
            com.fish5.features.impl.misc.AutoSell,
            ExploitPreventer,
            UngrabMouse,
            Fullbright,
            PlayerEsp,
            FreecamModule,
            FreelookModule,
            CustomSwingSpeed,
            HeldItem,
            PlayerSize,
            ProxyManager,
        )
    }

    fun registerModules(config: ModuleConfig, vararg mods: Module) {
        for (module in mods) {
            val lower = module.name.lowercase()
            config.modules[lower] = module
            modules[lower] = module
            modulesByCategory.getOrPut(module.category) { arrayListOf() }.add(module)
        }
        configs.add(config)
        config.load()
    }

    fun saveConfigurations() {
        for (config in configs) config.save()
    }

    fun loadConfigurations() {
        for (config in configs) config.load()
    }
}
