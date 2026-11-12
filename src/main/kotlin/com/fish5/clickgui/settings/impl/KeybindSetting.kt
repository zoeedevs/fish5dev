package com.fish5.clickgui.settings.impl

import com.fish5.clickgui.Theme
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import com.mojang.blaze3d.platform.InputConstants
import com.fish5.Fish5.mc
import com.fish5.clickgui.ClickGUI.gray38
import com.fish5.clickgui.settings.RenderableSetting
import com.fish5.clickgui.settings.Saving
import com.fish5.features.impl.render.ClickGUIModule
import com.fish5.utils.Colors
import com.fish5.utils.ui.isAreaHovered
import com.fish5.utils.ui.rendering.GGRenderer
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import org.lwjgl.glfw.GLFW

class KeybindSetting(
    name: String,
    override val default: InputConstants.Key,
    desc: String
) : RenderableSetting<InputConstants.Key>(name, desc), Saving {

    constructor(name: String, defaultKeyCode: Int, desc: String = "") : this(name, InputConstants.Type.KEYSYM.getOrCreate(defaultKeyCode), desc)

    override var value: InputConstants.Key = default
    var onPress: (() -> Unit)? = null
    private var keyNameWidth = -1f

    private var key: InputConstants.Key
        get() = value
        set(newKey) {
            if (newKey == value) return
            value = newKey
            keyNameWidth = GGRenderer.textWidth(keyLabel(), 16f, GGRenderer.defaultFont)
        }

    // key chip: label in a fully rounded dark pill, sized to its text.
    private fun label(): String = if (listening) "..." else keyLabel()
    private fun pillW() = GGRenderer.textWidth(label(), PILL_TEXT) + 22f
    private fun pillX() = lastX + width - 6f - pillW()
    private fun pillY() = lastY + getHeight() / 2f - PILL_H / 2f

    override fun inlineControlWidth(): Float = pillW() + 10f

    override fun render(x: Float, y: Float, mouseX: Float, mouseY: Float): Float {
        super.render(x, y, mouseX, mouseY)
        val height = getHeight()
        val text = label()
        keyNameWidth = GGRenderer.textWidth(text, PILL_TEXT)
        val px = pillX()
        val py = pillY()
        val pw = pillW()

        Theme.pill(px, py, pw, PILL_H, isHovered)
        if (listening) GGRenderer.hollowRect(px, py, pw, PILL_H, 1.5f, Theme.accent, PILL_H / 2f)

        if (!hideName) GGRenderer.text(name, x + 6f, y + height / 2f - 8f, 16f, Colors.WHITE.rgba, GGRenderer.defaultFont)
        GGRenderer.text(text, px + (pw - keyNameWidth) / 2f, py + PILL_H / 2f - PILL_TEXT / 2f + CAPS_NUDGE, PILL_TEXT, if (listening) Theme.accent else Colors.WHITE.rgba, GGRenderer.defaultFont)

        return height
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, click: MouseButtonEvent): Boolean {
        if (listening) {
            key = InputConstants.Type.MOUSE.getOrCreate(click.button())
            listening = false
            return true
        } else if (click.button() == 0 && isHovered) {
            listening = true
            return true
        }
        return false
    }

    override fun keyPressed(input: KeyEvent): Boolean {
        if (!listening) return false

        when (input.key) {
            GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_KEY_BACKSPACE -> key = InputConstants.UNKNOWN
            GLFW.GLFW_KEY_ENTER -> listening = false
            else -> key = InputConstants.getKey(input)
        }

        listening = false
        return true
    }

    /** "NONE" for an unbound key (Minecraft's own name for UNKNOWN is long and localized). */
    fun keyLabel(): String = if (value == InputConstants.UNKNOWN) "NONE" else value.displayName.string.uppercase()

    fun onPress(block: () -> Unit): KeybindSetting {
        onPress = block
        return this
    }



    override val isHovered: Boolean
        get() = isAreaHovered(pillX(), pillY(), pillW(), PILL_H, true)

    override fun write(gson: Gson): JsonElement = JsonPrimitive(value.name)

    override fun read(element: JsonElement, gson: Gson) {
        element.asString?.let { value = InputConstants.getKey(it) }
    }

    override fun reset() {
        value = default
    }

    companion object {
        private const val PILL_H = 26f
        private const val PILL_TEXT = 12.5f
        /** All-caps labels have no descenders, so they read high when line-box centred. */
        private const val CAPS_NUDGE = 0.75f

        fun InputConstants.Key.isDown(): Boolean {
            val window = mc.window
            return if (value > 7) InputConstants.isKeyDown(window, value)
            else GLFW.glfwGetMouseButton(window.handle(), value) == GLFW.GLFW_PRESS
        }
    }
}
