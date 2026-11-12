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
import com.fish5.utils.Colors
import com.fish5.utils.ui.TextInputHandler
import com.fish5.utils.ui.rendering.GGRenderer
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent

class StringSetting(
    name: String,
    override val default: String = "",
    private var length: Int = 32,
    desc: String
) : RenderableSetting<String>(name, desc), Saving {

    override var value: String = default
        set(value) {
            field = if (value.length <= length) value else return
        }

    private val textInputHandler = TextInputHandler(
        textProvider = { value },
        textSetter = { value = it }
    )

    override fun render(x: Float, y: Float, mouseX: Float, mouseY: Float): Float {
        super.render(x, y, mouseX, mouseY)

        val rectStartX = x + 6f

        GGRenderer.text(name, rectStartX, y + 5f, 16f, Colors.WHITE.rgba, GGRenderer.defaultFont)

        GGRenderer.rect(rectStartX, y + getHeight() - 35f, width - 12f, 30f, gray38.rgba, 4f)
        GGRenderer.hollowRect(rectStartX, y + getHeight() - 35f, width - 12f, 30f, 2f, Theme.border, 4f)

        textInputHandler.x = rectStartX
        textInputHandler.y = y + getHeight() - 30f
        textInputHandler.width = width - 16f
        textInputHandler.draw(mouseX, mouseY)

        return getHeight()
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, click: MouseButtonEvent): Boolean {
        return if (click.button() == 0) textInputHandler.mouseClicked(mouseX, mouseY, click)
        else false
    }

    override fun mouseReleased(click: MouseButtonEvent) {
        textInputHandler.mouseReleased()
    }

    override fun keyPressed(input: KeyEvent): Boolean {
        return textInputHandler.keyPressed(input)
    }

    override fun keyTyped(input: CharacterEvent): Boolean {
        return textInputHandler.keyTyped(input)
    }

    override fun getHeight(): Float = Panel.HEIGHT + 28f

    override fun write(gson: Gson): JsonElement = JsonPrimitive(value)

    override fun read(element: JsonElement, gson: Gson) {
        element.asString?.let { value = it }
    }
}