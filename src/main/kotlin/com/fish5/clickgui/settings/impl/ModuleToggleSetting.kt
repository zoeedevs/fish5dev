package com.fish5.clickgui.settings.impl

import com.fish5.clickgui.Theme
import com.fish5.clickgui.ClickGUI.gray38
import com.fish5.clickgui.settings.RenderableSetting
import com.fish5.features.Module
import com.fish5.features.impl.render.ClickGUIModule
import com.fish5.utils.Color.Companion.brighter
import com.fish5.utils.Colors
import com.fish5.utils.ui.animations.LinearAnimation
import com.fish5.utils.ui.isAreaHovered
import com.fish5.utils.ui.rendering.GGRenderer
import net.minecraft.client.input.MouseButtonEvent

/**
 * A toggle row bound directly to another [Module]'s own enabled state, for composite pages (like
 * composite cards) that gather several small modules' on/off switches
 * onto one settings page instead of each being its own card in the grid. Visually identical to
 * [BooleanSetting] -- reads/writes [target]'s real `enabled`/`toggle()` instead of an internal value,
 * so the grid-hidden sub-module and this row are always in sync, and its existing persistence (keyed
 * to the sub-module's own name in [com.fish5.config.ModuleConfig]) needs no changes.
 */
class ModuleToggleSetting(
    private val target: Module,
    name: String = target.name,
) : RenderableSetting<Boolean>(name, target.description) {

    override val default: Boolean = false
    override var value: Boolean
        get() = target.enabled
        set(v) { if (v != target.enabled) target.toggle() }

    private val toggleAnimation = LinearAnimation<Float>(200)

    override fun render(x: Float, y: Float, mouseX: Float, mouseY: Float): Float {
        super.render(x, y, mouseX, mouseY)
        val height = getHeight()
        val enabled = value

        if (!hideName) GGRenderer.text(name, x + 6f, y + height / 2f - 8f, 16f, Colors.WHITE.rgba)

        Theme.toggle(x + width - Theme.TOGGLE_W - 6f, y + height / 2f - Theme.TOGGLE_H / 2f, toggleAnimation.get(0f, 1f, !enabled), isHovered)

        return height
    }

    override fun inlineControlWidth(): Float = Theme.TOGGLE_W + 12f

    override fun mouseClicked(mouseX: Float, mouseY: Float, click: MouseButtonEvent): Boolean {
        return if (click.button() != 0 || !isHovered) false
        else {
            toggleAnimation.start()
            value = !value
            true
        }
    }

    override val isHovered: Boolean get() = isAreaHovered(lastX + width - Theme.TOGGLE_W - 6f, lastY + getHeight() / 2f - Theme.TOGGLE_H / 2f, Theme.TOGGLE_W, Theme.TOGGLE_H, true)
}
