package com.fish5.clickgui

import com.fish5.utils.ui.TextInputHandler
import com.fish5.utils.ui.rendering.GGRenderer
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent

/** Pill search field in the click GUI's header; a non-empty query swaps the page for search results. */
object SearchBar {

    var currentSearch = ""
        private set(value) {
            if (value.length > 24) return
            field = value
        }

    fun clear() { currentSearch = "" }

    private const val PLACEHOLDER = "Search..."

    private val textInputHandler = TextInputHandler(
        textProvider = { currentSearch },
        textSetter = { currentSearch = it }
    )

    fun draw(x: Float, y: Float, w: Float, h: Float, mouseX: Float, mouseY: Float) {
        GGRenderer.rect(x, y, w, h, Theme.control, h / 2f)
        GGRenderer.hollowRect(x, y, w, h, 1f, Theme.border, h / 2f)
        GGRenderer.icon(GuiLayout.iconSearch, x + 13f, y + h / 2f - 8f, 16f, Theme.textSecondary)

        val textX = x + 36f
        val textW = w - 36f - h / 2f
        // Clipped to the inside of the pill so neither the placeholder nor long input can spill out.
        GGRenderer.pushScissor(textX, y, textW, h)
        if (currentSearch.isEmpty()) GGRenderer.text(PLACEHOLDER, textX + 4f, y + h / 2f - 7f, 14f, Theme.textTertiary)
        textInputHandler.x = textX
        textInputHandler.y = y + h / 2f - 9f
        textInputHandler.width = textW
        textInputHandler.height = 16f
        textInputHandler.draw(mouseX, mouseY)
        GGRenderer.popScissor()
    }

    fun mouseClicked(mouseX: Float, mouseY: Float, click: MouseButtonEvent): Boolean =
        textInputHandler.mouseClicked(mouseX, mouseY, click)

    fun mouseReleased() = textInputHandler.mouseReleased()

    fun keyPressed(input: KeyEvent): Boolean = textInputHandler.keyPressed(input)

    fun keyTyped(input: CharacterEvent): Boolean = textInputHandler.keyTyped(input)
}
