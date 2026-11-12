package com.fish5.clickgui.settings.impl

import com.fish5.clickgui.Theme
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import com.fish5.clickgui.ClickGUI.gray38
import com.fish5.clickgui.Panel
import com.fish5.clickgui.settings.RenderableSetting
import com.fish5.clickgui.settings.Saving
import com.fish5.features.impl.render.ClickGUIModule
import com.fish5.utils.Color
import com.fish5.utils.Color.Companion.brighter
import com.fish5.utils.Colors
import com.fish5.utils.ui.HoverHandler
import com.fish5.utils.ui.animations.EaseInOutAnimation
import com.fish5.utils.ui.isAreaHovered
import com.fish5.utils.ui.rendering.GGRenderer
import net.minecraft.client.input.MouseButtonEvent

class SelectorSetting(
    name: String,
    default: String,
    private var options: List<String>,
    desc: String
) : RenderableSetting<Int>(name, desc), Saving {

    override val default: Int = optionIndex(default)

    override var value: Int
        get() = index
        set(value) {
            index = value
        }

    private var index: Int = optionIndex(default)
        set(value) {
            field = if (value > options.size - 1) 0 else if (value < 0) options.size - 1 else value
        }

    private var selected: String
        get() = options[index]
        set(value) {
            index = optionIndex(value)
        }

    private val elementWidths by lazy { options.map { GGRenderer.textWidth(it, 16f, GGRenderer.defaultFont) } }
    private val settingAnim = EaseInOutAnimation(200)
    private val hover = HoverHandler(150)
    private val defaultHeight = Panel.HEIGHT
    private var extended = false

    // dropdown chip: current value + chevron in a rounded dark pill.
    private fun chipW() = GGRenderer.textWidth(selected, CHIP_TEXT) + 44f
    private fun chipX() = lastX + width - 6f - chipW()
    private fun chipY() = lastY + defaultHeight / 2f - CHIP_H / 2f

    override fun inlineControlWidth(): Float = chipW() + 10f

    private val color: Color get() = gray38.brighter(1 + hover.percent() / 500f)

    private fun isSettingHovered(index: Int): Boolean =
        isAreaHovered(lastX, lastY + 38f + 32f * index, width, 32f, true)

    override fun render(x: Float, y: Float, mouseX: Float, mouseY: Float): Float {
        super.render(x, y, mouseX, mouseY)

        val cx = chipX()
        val cy = chipY()
        val cw = chipW()
        hover.handle(cx, cy, cw, CHIP_H, true)
        Theme.pill(cx, cy, cw, CHIP_H, hover.isHovered, 8f)
        GGRenderer.text(selected, cx + 12f, cy + CHIP_H / 2f - CHIP_TEXT / 2f, CHIP_TEXT, Colors.WHITE.rgba)
        GGRenderer.icon(chevron, cx + cw - 24f, cy + CHIP_H / 2f - 7f, 14f, Theme.textSecondary)

        if (!hideName) GGRenderer.text(name, x + 6f, y + defaultHeight / 2f - 8f, 16f, Colors.WHITE.rgba, GGRenderer.defaultFont)

        if (!extended && !settingAnim.isAnimating()) return defaultHeight

        val displayHeight = getHeight()
        if (settingAnim.isAnimating()) GGRenderer.pushScissor(x, y, width, displayHeight)

        GGRenderer.rect(x + 6, y + 37f, width - 12f, options.size * 32f, gray38.rgba, 5f)

        for (i in options.indices) {
            val optionY = y + 38 + 32 * i
            if (i != options.size - 1) GGRenderer.line(x + 18f, optionY + 32, x + width - 12f, optionY + 32, 1.5f, Colors.MINECRAFT_DARK_GRAY.rgba)
            GGRenderer.text(options[i], x + width / 2f - elementWidths[i] / 2, optionY + 8f, 16f, Colors.WHITE.rgba, GGRenderer.defaultFont)
            if (isSettingHovered(i)) GGRenderer.hollowRect(x + 6, optionY, width - 12f, 32f, 1.5f, Theme.border, 4f)
        }
        if (settingAnim.isAnimating()) GGRenderer.popScissor()

        return displayHeight
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, click: MouseButtonEvent): Boolean {
        if (click.button() == 0) {
            if (isHovered) {
                settingAnim.start()
                extended = !extended
                return true
            }

            if (!extended) return false

            for (index in options.indices) {
                if (isSettingHovered(index)) {
                    settingAnim.start()
                    selected = options[index]
                    extended = false
                    return true
                }
            }
        } else if (click.button() == 1) {
            if (isHovered) {
                index++
                return true
            }
        }
        return false
    }

    private fun optionIndex(string: String): Int =
        options.map { it.lowercase() }.indexOf(string.lowercase()).coerceIn(0, options.size - 1)

    override val isHovered: Boolean get() = isAreaHovered(lastX, lastY, width, defaultHeight, true)

    override fun getHeight(): Float =
        settingAnim.get(defaultHeight, options.size * 32f + 44, !extended)

    override fun write(gson: Gson): JsonElement = JsonPrimitive(selected)

    override fun read(element: JsonElement, gson: Gson) {
        element.asString?.let { selected = it }
    }

    companion object {
        private const val CHIP_H = 26f
        private const val CHIP_TEXT = 13f
        private val chevron = GGRenderer.createImage("/assets/fish5/icons/chevron_down.svg")
    }
}