package com.fish5.features.impl.render

import com.fish5.features.Category
import com.fish5.features.Module

/** Read by [com.fish5.mixin.LightmapFullbrightMixin]. */
object Fullbright : Module(
    name = "Fullbright",
    category = Category.RENDER,
    description = "Force full brightness so dark areas are fully lit. Pure client-side; works with cheats off.",
)
