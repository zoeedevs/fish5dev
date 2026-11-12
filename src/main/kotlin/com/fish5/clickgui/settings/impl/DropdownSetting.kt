package com.fish5.clickgui.settings.impl

import com.fish5.clickgui.ClickGUI
import com.fish5.clickgui.settings.RenderableSetting
import com.fish5.utils.Colors
import com.fish5.utils.ui.HoverHandler
import com.fish5.utils.ui.animations.LinearAnimation
import com.fish5.utils.ui.isAreaHovered
import com.fish5.utils.ui.rendering.GGRenderer
import net.minecraft.client.input.MouseButtonEvent

/**
 * A setting intended to show or hide other settings in the GUI.
 *
 * @author Bonsai
 */
class DropdownSetting(
    name: String,
    override val default: Boolean = false,
    desc: String = ""
) : RenderableSetting<Boolean>(name, desc) {

    override var value: Boolean = default
    private var enabled: Boolean by this::value

    private val toggleAnimation = LinearAnimation<Float>(200)
    private val hoverHandler = HoverHandler(150)

    override fun render(x: Float, y: Float, mouseX: Float, mouseY: Float): Float {
        super.render(x, y, mouseX, mouseY)
        val height = getHeight()

        GGRenderer.text(name, x + 6f, y + height / 2f - 8f, 16f, Colors.WHITE.rgba, GGRenderer.defaultFont)

        hoverHandler.handle(lastX + width - 30f, lastY + getHeight() / 2f - 16f, 24f, 24f, true)

        val imageSize = 24f + (6f * hoverHandler.percent() / 100f)
        val offset = (imageSize - 24f) / 2f

        GGRenderer.push()
        GGRenderer.translate(x + width - 22f, y + height / 2f)
        GGRenderer.rotate(toggleAnimation.get(Math.PI.toFloat() / 2f, 0f, enabled))
        GGRenderer.translate(-(12f + offset), -(12f + offset))
        GGRenderer.image(ClickGUI.chevronImage, 0f, 0f, imageSize, imageSize)
        GGRenderer.pop()

        return height
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, click: MouseButtonEvent): Boolean {
        if (click.button() != 0 || !isHovered) return false
        enabled = !enabled
        toggleAnimation.start()
        return true
    }

    override val isHovered: Boolean get() = isAreaHovered(lastX + width - 30f, lastY + getHeight() / 2f - 16f, 24f, 24f, true)
}