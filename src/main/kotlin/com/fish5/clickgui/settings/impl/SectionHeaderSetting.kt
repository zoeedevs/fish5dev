package com.fish5.clickgui.settings.impl

import com.fish5.clickgui.Theme
import com.fish5.clickgui.settings.RenderableSetting
import com.fish5.utils.Colors
import com.fish5.utils.ui.rendering.GGRenderer
import net.minecraft.client.input.MouseButtonEvent

/**
 * Non-interactive sub-group divider for composite settings pages, e.g.
 * grouped sections. Doesn't call
 * `super.render` -- a header has no hover tooltip or click area, so it skips [RenderableSetting]'s
 * hover-handling entirely rather than faking a no-op one.
 */
class SectionHeaderSetting(label: String) : RenderableSetting<Unit>(label, "") {

    override val default: Unit = Unit
    override var value: Unit = Unit

    override fun render(x: Float, y: Float, mouseX: Float, mouseY: Float): Float {
        GGRenderer.text(name.uppercase(), x + 2f, y + 8f, 12f, Theme.textSecondary)
        return getHeight()
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, click: MouseButtonEvent): Boolean = false
    override fun getHeight(): Float = 24f
    override val isHovered: Boolean get() = false
}
