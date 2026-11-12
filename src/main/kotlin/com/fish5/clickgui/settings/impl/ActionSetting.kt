package com.fish5.clickgui.settings.impl

import com.fish5.clickgui.Theme
import com.fish5.clickgui.ClickGUI.gray38
import com.fish5.clickgui.settings.RenderableSetting
import com.fish5.features.impl.render.ClickGUIModule
import com.fish5.utils.Color.Companion.darker
import com.fish5.utils.Colors
import com.fish5.utils.ui.rendering.GGRenderer
import net.minecraft.client.input.MouseButtonEvent

class ActionSetting(
    name: String,
    desc: String,
    override val default: () -> Unit = {}
) : RenderableSetting<() -> Unit>(name, desc) {

    override var value: () -> Unit = default

    var action: () -> Unit by this::value

    private val textWidth by lazy { GGRenderer.textWidth(name, 16f, GGRenderer.defaultFont) }

    override fun render(x: Float, y: Float, mouseX: Float, mouseY: Float): Float {
        super.render(x, y, mouseX, mouseY)
        val height = getHeight()

        GGRenderer.rect(x + 4f, y + height / 2f - 13f, width - 8f, 26f, if (isHovered) Theme.controlHover else Theme.control, 8f)
        GGRenderer.hollowRect(x + 4f, y + height / 2f - 13f, width - 8f, 26f, 2f, Theme.border, 6f)
        GGRenderer.text(name, x + width / 2f - textWidth / 2, y + height / 2f - 8f, 16f, if (isHovered) Colors.WHITE.darker().rgba else Colors.WHITE.rgba, GGRenderer.defaultFont)
        return height
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, click: MouseButtonEvent): Boolean {
        return if (click.button() != 0 || !isHovered) false
        else {
            action()
            true
        }
    }
}