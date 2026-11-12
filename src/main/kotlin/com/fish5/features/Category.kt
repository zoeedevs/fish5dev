package com.fish5.features

/**
 * Panels in the click GUI are built one-per-category, in insertion order.
 */
@ConsistentCopyVisibility
data class Category private constructor(val name: String) {
    companion object {
        /** All categories, keyed by name; iteration order defines panel order left-to-right. */
        val categories: LinkedHashMap<String, Category> = linkedMapOf()

        @JvmField val RENDER = custom("Render")
        @JvmField val PLAYER = custom("Player")
        @JvmField val SKYBLOCK = custom("Skyblock")
        @JvmField val MODULES = custom("Modules")
        @JvmField val OPSEC = custom("Opsec")

        fun custom(name: String): Category = categories.getOrPut(name) { Category(name) }
    }
}
