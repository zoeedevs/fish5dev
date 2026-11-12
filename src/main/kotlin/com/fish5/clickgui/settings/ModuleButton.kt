package com.fish5.clickgui.settings

import com.fish5.Fish5
import com.fish5.clickgui.ClickGUI
import com.fish5.clickgui.ClickGUI.gray26
import com.fish5.clickgui.Panel
import com.fish5.features.Module
import com.fish5.features.impl.render.ClickGUIModule
import com.fish5.utils.Color
import com.fish5.utils.Color.Companion.brighter
import com.fish5.utils.Colors
import com.fish5.utils.ui.HoverHandler
import com.fish5.utils.ui.animations.ColorAnimation
import com.fish5.utils.ui.animations.EaseInOutAnimation
import com.fish5.utils.ui.mouseX
import com.fish5.utils.ui.mouseY
import com.fish5.utils.ui.rendering.GGRenderer
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.MouseButtonEvent
import kotlin.math.floor

/**
 * Renders all the modules.
 *
 * @author Stivais, Aton
 *
 * see [RenderableSetting]
 */
class ModuleButton(val module: Module, val panel: Panel) {

    val representableSettings = module.settings.values.filterIsInstance<RenderableSetting<*>>()
    private val colorAnim = ColorAnimation(150)

    private val color: Color
        get() =
            colorAnim.get(ClickGUIModule.clickGUIColor, gray26, module.enabled).brighter(1 + hover.percent() / 500f)

    private val nameWidth = GGRenderer.textWidth(module.name, 18f, GGRenderer.defaultFont)
    private val hoverHandler = HoverHandler(750)
    private val extendAnim = EaseInOutAnimation(250)
    private val hover = HoverHandler(250)
    var extended = false

    fun draw(x: Float, y: Float): Float {
        hoverHandler.handle(x, y, Panel.WIDTH, Panel.HEIGHT - 1, true)
        hover.handle(x, y, Panel.WIDTH, Panel.HEIGHT - 1, true)

        if (hoverHandler.percent() >= 100 && y >= panel.panelSetting.y + Panel.HEIGHT)
            ClickGUI.setDescription(module.description, x + Panel.WIDTH + 10f, y, hoverHandler)

       GGRenderer.rect(x, y, Panel.WIDTH, Panel.HEIGHT, color.rgba)
        GGRenderer.text(module.name, x + Panel.WIDTH / 2 - nameWidth / 2, y + Panel.HEIGHT / 2 - 9f, 18f, Colors.WHITE.rgba, GGRenderer.defaultFont)

        if (representableSettings.isEmpty()) return Panel.HEIGHT

        val totalHeight = Panel.HEIGHT + floor(extendAnim.get(0f, getSettingHeight(), !extended))
        var drawY = Panel.HEIGHT

        if (extendAnim.isAnimating()) GGRenderer.pushScissor(x, y, Panel.WIDTH, totalHeight)

        if (extendAnim.isAnimating() || extended) {
            for (setting in representableSettings) {
                if (setting.isVisible) drawY += setting.render(x, y + drawY, mouseX / ClickGUIModule.getStandardGuiScale(), mouseY / ClickGUIModule.getStandardGuiScale())
            }
        }

        if (extendAnim.isAnimating()) GGRenderer.popScissor()
        return totalHeight
    }

    fun mouseClicked(mouseX: Float, mouseY: Float, click: MouseButtonEvent): Boolean {
        if (hover.isHovered) {
            if (click.button() == 0) {
                colorAnim.start()
                // Enabling a module closes the click GUI first, so it starts with the game screen in
                // front (macros like auto forge need the world, not this menu).
                if (!module.enabled) Fish5.mc.gui.setScreen(null)
                module.toggle()
                return true
            } else if (click.button() == 1) {
                if (module.settings.isNotEmpty()) {
                    extendAnim.start()
                    extended = !extended
                }
                return true
            }
        } else if (extended) {
            for (setting in representableSettings) {
                if (setting.isVisible && setting.mouseClicked(mouseX, mouseY, click)) return true
            }
        }
        return false
    }

    fun mouseReleased(click: MouseButtonEvent) {
        if (!extended) return
        for (setting in representableSettings) {
            if (setting.isVisible) setting.mouseReleased(click)
        }
    }

    fun keyTyped(input: CharacterEvent): Boolean {
        if (!extended) return false
        for (setting in representableSettings) {
            if (setting.isVisible && setting.keyTyped(input)) return true
        }
        return false
    }

    fun keyPressed(input: net.minecraft.client.input.KeyEvent): Boolean {
        if (!extended) return false
        for (setting in representableSettings) {
            if (setting.isVisible && setting.keyPressed(input)) return true
        }
        return false
    }

    private fun getSettingHeight(): Float =
        representableSettings.filter { it.isVisible }.sumOf { it.getHeight().toDouble() }.toFloat()
}