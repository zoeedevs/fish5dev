package com.fish5.clickgui.settings.impl

import com.fish5.clickgui.settings.RenderableSetting
import net.minecraft.client.input.MouseButtonEvent

/**
 * Title of a settings card that other settings sit inside (they point at it with `inGroup(...)`).
 * Draws no control: the click GUI writes the name + description as the card's header row, and the
 * grouped settings stack underneath it in the same card.
 */
class GroupSetting(name: String, desc: String = "") : RenderableSetting<Unit>(name, desc) {

    override val default: Unit = Unit
    override var value: Unit = Unit

    // > 0 so the click GUI lays it out as an inline name + description row
    override fun inlineControlWidth(): Float = 1f

    override fun render(x: Float, y: Float, mouseX: Float, mouseY: Float): Float {
        lastX = x
        lastY = y
        return getHeight()
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, click: MouseButtonEvent): Boolean = false
    override val isHovered: Boolean get() = false
}
