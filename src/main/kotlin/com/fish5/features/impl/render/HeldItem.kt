package com.fish5.features.impl.render

import com.fish5.clickgui.settings.impl.NumberSetting
import com.fish5.features.Category
import com.fish5.features.Module

/** Read by [com.fish5.mixin.ItemInHandRendererTransformMixin]. */
object HeldItem : Module(
    name = "Held Item",
    category = Category.RENDER,
    description = "Reposition and resize your first-person held item.",
) {
    val size by NumberSetting("Item Size", 1f, 0.1f, 3f, 0.05f, desc = "Held item scale. 1.0 = vanilla.")
    val posX by NumberSetting("X Placement", 0f, -1.5f, 1.5f, 0.01f, desc = "Horizontal offset (right positive). 0 = vanilla.")
    val posY by NumberSetting("Y Placement", 0f, -1.5f, 1.5f, 0.01f, desc = "Vertical offset (up positive). 0 = vanilla.")
    val posZ by NumberSetting("Z Placement", 0f, -1.5f, 1.5f, 0.01f, desc = "Depth offset (toward you positive). 0 = vanilla.")
}
