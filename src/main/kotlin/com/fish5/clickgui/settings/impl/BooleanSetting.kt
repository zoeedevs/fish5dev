package com.fish5.clickgui.settings.impl

import com.fish5.clickgui.Theme
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import com.fish5.clickgui.ClickGUI.gray38
import com.fish5.clickgui.settings.RenderableSetting
import com.fish5.clickgui.settings.Saving
import com.fish5.features.impl.render.ClickGUIModule
import com.fish5.utils.Color.Companion.brighter
import com.fish5.utils.Colors
import com.fish5.utils.ui.animations.LinearAnimation
import com.fish5.utils.ui.isAreaHovered
import com.fish5.utils.ui.rendering.GGRenderer
import net.minecraft.client.input.MouseButtonEvent

class BooleanSetting(
    name: String,
    override val default: Boolean = false,
    desc: String,
) : RenderableSetting<Boolean>(name, desc), Saving {

    override var value: Boolean = default
    var enabled: Boolean by this::value

    private val toggleAnimation = LinearAnimation<Float>(200)

    override fun render(x: Float, y: Float, mouseX: Float, mouseY: Float): Float {
        super.render(x, y, mouseX, mouseY)
        val height = getHeight()

        if (!hideName) GGRenderer.text(name, x + 6f, y + height / 2f - 8f, 16f, Colors.WHITE.rgba, GGRenderer.defaultFont)

        Theme.toggle(x + width - Theme.TOGGLE_W - 6f, y + height / 2f - Theme.TOGGLE_H / 2f, toggleAnimation.get(0f, 1f, !enabled), isHovered)

        return height
    }

    override fun inlineControlWidth(): Float = Theme.TOGGLE_W + 12f

    override fun mouseClicked(mouseX: Float, mouseY: Float, click: MouseButtonEvent): Boolean {
        return if (click.button() != 0 || !isHovered) false
        else {
            toggleAnimation.start()
            enabled = !enabled
            true
        }
    }

    override val isHovered: Boolean get() = isAreaHovered(lastX + width - Theme.TOGGLE_W - 6f, lastY + getHeight() / 2f - Theme.TOGGLE_H / 2f, Theme.TOGGLE_W, Theme.TOGGLE_H, true)

    override fun write(gson: Gson): JsonElement = JsonPrimitive(enabled)

    override fun read(element: JsonElement, gson: Gson) {
        enabled = element.asBoolean
    }
}