package com.fish5.features.impl.render

import com.fish5.clickgui.settings.impl.NumberSetting
import com.fish5.features.Category
import com.fish5.features.Module

/** Ported from Odin. Read by [com.fish5.mixin.AvatarRendererMixin]. */
object PlayerSize : Module(
    name = "Player Size",
    category = Category.PLAYER,
    description = "Rescale your own player model (third-person / other players' view). Purely client-side.",
) {
    val sizeX by NumberSetting("Size X", 1f, -1f, 3f, 0.1f, desc = "Width scale of your player model. 1.0 = vanilla.")
    val sizeY by NumberSetting("Size Y", 1f, -1f, 3f, 0.1f, desc = "Height scale. 1.0 = vanilla; negative flips upside-down.")
    val sizeZ by NumberSetting("Size Z", 1f, -1f, 3f, 0.1f, desc = "Depth scale of your player model. 1.0 = vanilla.")
}
