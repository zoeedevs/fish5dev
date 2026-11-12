package com.fish5.clickgui.settings.impl

import com.fish5.clickgui.ClickGUI.gray38
import com.fish5.clickgui.Panel
import com.fish5.clickgui.Theme
import com.fish5.clickgui.settings.RenderableSetting
import com.fish5.clickgui.settings.Saving
import com.fish5.utils.Colors
import com.fish5.utils.ui.TextInputHandler
import com.fish5.utils.ui.isAreaHovered
import com.fish5.utils.ui.rendering.GGRenderer
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import org.lwjgl.glfw.GLFW

/**
 * A list of strings the user edits in the GUI: type into the box and press Enter (or +) to add, click
 * the x on a row to remove it. Saved as a JSON array. Case-insensitive duplicates are skipped.
 */
class StringListSetting(
    name: String,
    desc: String,
    private val maxItems: Int = 64,
    private val maxLength: Int = 48,
    private val placeholder: String = "Type, then Enter",
) : RenderableSetting<MutableList<String>>(name, desc), Saving {

    override val default: MutableList<String> = arrayListOf()
    override var value: MutableList<String> = arrayListOf()

    private var draft = ""
    private var input = newInput()
    private val rowH = 22f
    private val inputH = 30f

    private fun newInput() = TextInputHandler(
        textProvider = { draft },
        textSetter = { if (it.length <= maxLength) draft = it },
    )

    /** Adds [item] (trimmed). False if empty, a duplicate, or the list is full. */
    fun add(item: String): Boolean {
        val t = item.trim()
        if (t.isEmpty() || value.size >= maxItems) return false
        for (v in value) if (v.equals(t, true)) return false
        value.add(t)
        return true
    }

    private fun commitDraft() {
        if (add(draft)) {
            draft = ""
            input = newInput()  // fresh caret/selection for the emptied text
        }
    }

    override fun render(x: Float, y: Float, mouseX: Float, mouseY: Float): Float {
        super.render(x, y, mouseX, mouseY)
        val left = x + 6f
        val w = width - 12f
        GGRenderer.text("$name (${value.size})", left, y + 5f, 16f, Colors.WHITE.rgba, GGRenderer.defaultFont)

        var ry = y + Panel.HEIGHT - 6f
        for (i in value.indices) {
            val hovered = isAreaHovered(left, ry, w, rowH, true)
            GGRenderer.rect(left, ry, w, rowH - 2f, gray38.rgba, 4f)
            if (hovered) GGRenderer.hollowRect(left, ry, w, rowH - 2f, 1.5f, Theme.border, 4f)
            GGRenderer.text(value[i], left + 8f, ry + 3f, 14f, Colors.WHITE.rgba, GGRenderer.defaultFont)
            GGRenderer.text("×", left + w - 16f, ry + 2f, 15f,
                (if (isRemoveHovered(left, ry, w)) Colors.MINECRAFT_RED else Colors.MINECRAFT_GRAY).rgba, GGRenderer.defaultFont)
            ry += rowH
        }

        val boxW = w - 34f
        GGRenderer.rect(left, ry, boxW, inputH, gray38.rgba, 4f)
        GGRenderer.hollowRect(left, ry, boxW, inputH, 2f, Theme.border, 4f)
        if (draft.isEmpty()) GGRenderer.text(placeholder, left + 6f, ry + 7f, 14f, Colors.MINECRAFT_GRAY.rgba, GGRenderer.defaultFont)
        input.x = left
        input.y = ry + 5f
        input.width = boxW - 4f
        input.draw(mouseX, mouseY)

        val bx = left + boxW + 4f
        GGRenderer.rect(bx, ry, 30f, inputH, (if (isAreaHovered(bx, ry, 30f, inputH, true)) Theme.controlHover else Theme.control), 4f)
        GGRenderer.hollowRect(bx, ry, 30f, inputH, 2f, Theme.border, 4f)
        GGRenderer.text("+", bx + 10f, ry + 6f, 16f, Colors.WHITE.rgba, GGRenderer.defaultFont)
        return getHeight()
    }

    private fun isRemoveHovered(left: Float, rowY: Float, w: Float) = isAreaHovered(left + w - 24f, rowY, 24f, rowH, true)

    override fun mouseClicked(mouseX: Float, mouseY: Float, click: MouseButtonEvent): Boolean {
        if (click.button() != 0) return false
        val left = lastX + 6f
        val w = width - 12f
        var ry = lastY + Panel.HEIGHT - 6f
        for (i in value.indices) {
            if (isRemoveHovered(left, ry, w)) { value.removeAt(i); return true }
            ry += rowH
        }
        if (isAreaHovered(left + w - 30f, ry, 30f, inputH, true)) { commitDraft(); return true }
        return input.mouseClicked(mouseX, mouseY, click)
    }

    override fun mouseReleased(click: MouseButtonEvent) = input.mouseReleased()

    override fun keyPressed(input: KeyEvent): Boolean {
        val handled = this.input.keyPressed(input)
        if (handled && (input.key == GLFW.GLFW_KEY_ENTER || input.key == GLFW.GLFW_KEY_KP_ENTER)) commitDraft()
        return handled
    }

    override fun keyTyped(input: CharacterEvent): Boolean = this.input.keyTyped(input)

    override fun getHeight(): Float = Panel.HEIGHT - 6f + value.size * rowH + inputH + 6f

    override fun write(gson: Gson): JsonElement {
        val arr = JsonArray()
        for (s in value) arr.add(s)
        return arr
    }

    override fun read(element: JsonElement, gson: Gson) {
        if (!element.isJsonArray) return
        value.clear()
        for (e in element.asJsonArray) runCatching { add(e.asString) }
    }
}
