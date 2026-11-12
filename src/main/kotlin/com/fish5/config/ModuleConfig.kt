package com.fish5.config

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.fish5.clickgui.settings.Saving
import com.fish5.features.Macro
import com.fish5.features.Module
import java.io.File

/**
 * Saves modules + their [Saving] settings to a JSON array on disk.
 */
class ModuleConfig(file: File) {

    // key is module name in lowercase
    internal val modules: HashMap<String, Module> = hashMapOf()

    private val file: File = file.apply {
        try {
            parentFile?.mkdirs()
            createNewFile()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun load() {
        try {
            with(file.bufferedReader().use { it.readText() }) {
                if (isEmpty()) return

                val jsonArray = JsonParser.parseString(this).asJsonArray ?: return
                val savedNames = jsonArray.mapNotNull { it?.asJsonObject?.get("name")?.asString?.lowercase() }.toSet()
                for (modules in jsonArray) {
                    val moduleObj = modules?.asJsonObject ?: continue
                    val savedName = moduleObj.get("name").asString.lowercase()
                    var module = this@ModuleConfig.modules[savedName]
                    var adoptEnabled = true
                    if (module == null) {
                        // entry saved under a module's old name: migrate it unless the new name was already saved
                        module = this@ModuleConfig.modules.values.firstOrNull { m ->
                            m.legacyNames.any { it.lowercase() == savedName }
                        } ?: continue
                        if (module.name.lowercase() in savedNames) continue
                        adoptEnabled = module.legacyNames.first().lowercase() == savedName
                    }
                    // a macro's running state is never restored: it must not start by itself on launch
                    if (adoptEnabled && module !is Macro && moduleObj.get("enabled").asBoolean != module.enabled) module.toggle()
                    val settingObj = moduleObj.get("settings")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet() ?: continue
                    for ((key, value) in settingObj) {
                        (module.settings[key] as? Saving)?.apply { read(value ?: continue, gson) }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun save() {
        try {
            val jsonArray = JsonArray().apply {
                for ((_, module) in modules) {
                    add(JsonObject().apply {
                        add("name", JsonPrimitive(module.name))
                        add("enabled", JsonPrimitive(module.enabled && module !is Macro))
                        add("settings", JsonObject().apply {
                            for ((name, setting) in module.settings) {
                                if (setting is Saving) add(name, setting.write(gson))
                            }
                        })
                    })
                }
            }
            file.bufferedWriter().use { it.write(gson.toJson(jsonArray)) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private companion object {
        private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    }

    override fun toString(): String = "ModuleConfig(file=$file)"
}
