package com.fish5.hud

import com.fish5.Fish5.mc
import com.fish5.features.ModuleManager
import com.fish5.features.impl.render.ClickGUIModule
import com.fish5.utils.Color
import com.fish5.utils.Colors
import com.fish5.utils.ui.rendering.GGRenderer
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import com.fish5.utils.ui.mouseX as guiMouseX
import com.fish5.utils.ui.mouseY as guiMouseY

/**
 * The unified HUD editor: one screen that draws every registered [HudElement] (the wormfish session,
 * lifetime and forge panels) at its position and lets you drag each one. Works in the standard
 * GUI-scale NanoVG space the overlays use. Positions persist via each feature's own settings, saved on
 * close.
 */
class HudEditorScreen(private val parent: Screen?) : Screen(Component.literal("HUD Editor")) {

    private var dragging: HudElement? = null
    private var dragOffX = 0f
    private var dragOffY = 0f

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        context.fill(0, 0, context.guiWidth(), context.guiHeight(), 0xB0000000.toInt())
        GGRenderer.frame(context) {
            val scale = ClickGUIModule.getStandardGuiScale()
            GGRenderer.scale(scale, scale)
            val smx = (guiMouseX / scale).toFloat()
            val smy = (guiMouseY / scale).toFloat()

            dragging?.let {
                it.hudX = (smx - dragOffX).toInt().coerceIn(0, 1920)
                it.hudY = (smy - dragOffY).toInt().coerceIn(0, 1080)
            }

            GGRenderer.text("HUD Editor — drag to move, Esc to save", 6f, 6f, 14f, Colors.WHITE.rgba, GGRenderer.defaultFont)

            for (e in HudManager.elements) {
                e.renderHudElement(true)
                val hovered = contains(e, smx, smy)
                val col = if (e === dragging || hovered) ClickGUIModule.clickGUIColor.rgba else Color(255, 255, 255, 0.5f).rgba
                GGRenderer.hollowRect(e.hudX.toFloat(), e.hudY.toFloat(), e.hudWidth(), e.hudHeight(), 1f, col, 3f)
                GGRenderer.text(e.hudLabel, e.hudX.toFloat(), e.hudY.toFloat() - 10f, 9f, Colors.WHITE.rgba, GGRenderer.defaultFont)
            }
        }
        super.extractRenderState(context, mouseX, mouseY, deltaTicks)
    }

    private fun contains(e: HudElement, mx: Float, my: Float): Boolean =
        mx >= e.hudX.toFloat() && mx <= e.hudX + e.hudWidth() && my >= e.hudY.toFloat() && my <= e.hudY + e.hudHeight()

    override fun mouseClicked(event: MouseButtonEvent, bl: Boolean): Boolean {
        if (event.button() == 0) {
            val scale = ClickGUIModule.getStandardGuiScale()
            val smx = (guiMouseX / scale).toFloat()
            val smy = (guiMouseY / scale).toFloat()
            for (e in HudManager.elements.asReversed()) {
                if (contains(e, smx, smy)) {
                    dragging = e
                    dragOffX = smx - e.hudX
                    dragOffY = smy - e.hudY
                    return true
                }
            }
        }
        return super.mouseClicked(event, bl)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        dragging = null
        return super.mouseReleased(event)
    }

    override fun isPauseScreen(): Boolean = false

    override fun onClose() {
        ModuleManager.saveConfigurations()
        mc.gui.setScreen(parent)
    }
}
