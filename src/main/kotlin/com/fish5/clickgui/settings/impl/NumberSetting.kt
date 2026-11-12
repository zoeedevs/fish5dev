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
import com.fish5.utils.ui.HoverHandler
import com.fish5.utils.ui.animations.LinearAnimation
import com.fish5.utils.ui.isAreaHovered
import com.fish5.utils.ui.rendering.GGRenderer
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import org.lwjgl.glfw.GLFW
import kotlin.math.floor
import kotlin.math.round
import kotlin.math.roundToInt

/**
 * Setting that lets you pick a number between a range.
 *
 * With [parseInput] set, clicking the value (top right) also lets you type it: Enter applies, Esc or
 * clicking elsewhere cancels. The parsed number is rounded to the increment and clamped like a drag.
 * @author Stivais, Aton
 */
@Suppress("UNCHECKED_CAST")
class NumberSetting<E>(
    name: String,
    override val default: E = 1.0 as E,
    min: Number,
    max: Number,
    increment: Number = 1,
    desc: String,
    private val unit: String = "",
    private val parseInput: ((String) -> Double?)? = null,
) : RenderableSetting<E>(name, desc), Saving where E : Number, E : Comparable<E> {

    private val incrementDouble = increment.toDouble()
    private val minDouble = min.toDouble()
    private var maxDouble = max.toDouble()

    private val sliderAnim = LinearAnimation<Float>(100)
    private val handler = HoverHandler(150)

    private var displayValue = ""
    private var prevLocation = 0f
    private var valueWidth = -1f
    private var isDragging = false

    // typed entry (only with parseInput)
    private val typed = StringBuilder()
    private var typing = false

    private var sliderPercentage = 0f
        set(value) {
            if (sliderPercentage != value) {
                if (!isDragging) {
                    prevLocation = sliderAnim.get(prevLocation, sliderPercentage, false)
                    sliderAnim.start()
                }
                displayValue = getDisplay()
                valueWidth = -1f
            }
            field = value
        }

    override var value: E = default
        set(value) {
            field = roundToIncrement(value).coerceIn(minDouble, maxDouble) as E
            sliderPercentage = ((field.toDouble() - minDouble) / (maxDouble - minDouble)).toFloat()
        }

    init {
        value = default
        displayValue = getDisplay()
    }

    private var valueDouble
        get() = value.toDouble()
        set(value) {
            this.value = value as E
        }

    private var valueInt
        get() = value.toInt()
        set(value) {
            this.value = value as E
        }

    override fun render(x: Float, y: Float, mouseX: Float, mouseY: Float): Float {
        super.render(x, y, mouseX, mouseY)
        val height = getHeight()

        handler.handle(x, y + height / 2, width, height / 2, true)

        if (listening) {
            val newPercentage = ((mouseX - (x + 6f)) / (width - 12f)).coerceIn(0f, 1f)
            valueDouble = minDouble + newPercentage * (maxDouble - minDouble)
            sliderPercentage = newPercentage
        }

        if (valueWidth < 0) {
            valueWidth = GGRenderer.textWidth(displayValue, 16f, GGRenderer.defaultFont)
        }

        GGRenderer.text(name, x + 6f, y + height / 2f - 15f, 16f, Colors.WHITE.rgba, GGRenderer.defaultFont)
        if (typing) {
            val shown = typed.toString()
            val w = GGRenderer.textWidth(shown, 16f, GGRenderer.defaultFont)
            val boxW = maxOf(w + 10f, 40f)
            val bx = x + width - boxW - 2f
            GGRenderer.rect(bx, y + height / 2f - 17f, boxW, 19f, Theme.control, 4f)
            GGRenderer.text(shown, bx + 5f, y + height / 2f - 15f, 16f, Colors.WHITE.rgba, GGRenderer.defaultFont)
            if (System.currentTimeMillis() % 1000 < 500) {
                val cx = bx + 5f + w + 1f
                GGRenderer.line(cx, y + height / 2f - 15f, cx, y + height / 2f, 2f, Colors.WHITE.rgba)
            }
        } else {
            GGRenderer.text(displayValue, x + width - valueWidth - 4f, y + height / 2f - 15f, 16f, Colors.WHITE.rgba, GGRenderer.defaultFont)
        }

        GGRenderer.rect(x + 6f, y + 24f, width - 12f, 8f, Theme.control, 4f)

        if (x + sliderPercentage * (width - 12f) > x + 6)
            GGRenderer.rect(x + 6f, y + 24f, sliderAnim.get(prevLocation, sliderPercentage, false) * (width - 12f), 8f, Theme.argb(255, 201, 203, 210), 4f)

        GGRenderer.circle(x + 6f + sliderAnim.get(prevLocation, sliderPercentage, false) * (width - 12f), y + 28f, handler.anim.get(7f, 9f, !isHovered), Colors.WHITE.rgba)

        return height
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, click: MouseButtonEvent): Boolean {
        if (parseInput != null) {
            val onValue = click.button() == 0 &&
                isAreaHovered(lastX + width * 0.5f, lastY, width * 0.5f, getHeight() / 2, true)
            if (onValue) {
                if (!typing) {
                    typing = true
                    typed.setLength(0)
                    typed.append(displayValue.removeSuffix(unit))
                }
                return true
            }
            if (typing) typing = false  // clicked elsewhere: cancel
        }
        return if (click.button() != 0 || !isHovered) false
        else {
            listening = true
            isDragging = true
            prevLocation = sliderPercentage
            sliderAnim.start()
            true
        }
    }

    override fun mouseReleased(click: MouseButtonEvent) {
        listening = false
        if (isDragging) {
            isDragging = false
            prevLocation = sliderAnim.get(prevLocation, sliderPercentage, false)
            sliderAnim.start()
        }
    }

    override fun keyTyped(input: CharacterEvent): Boolean {
        if (!typing) return false
        val c = input.codepointAsString()
        if (c.length == 1 && (c[0].isDigit() || c[0] in ".,kKmM") && typed.length < 16) typed.append(c)
        return true
    }

    override fun keyPressed(input: KeyEvent): Boolean {
        if (typing) {
            when (input.key) {
                GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                    parseInput?.invoke(typed.toString().trim())?.let {
                        valueDouble = it.coerceIn(minDouble, maxDouble)
                        displayValue = getDisplay()
                        valueWidth = -1f
                    }
                    typing = false
                }
                GLFW.GLFW_KEY_ESCAPE -> typing = false
                GLFW.GLFW_KEY_BACKSPACE -> if (typed.isNotEmpty()) typed.setLength(typed.length - 1)
            }
            return true  // swallow everything while typing (Esc must not close the GUI)
        }
        if (!isHovered) return false

        val amount = when (input.key) {
            GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_EQUAL -> incrementDouble
            GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_MINUS -> -incrementDouble
            else -> return false
        }

        if (valueDouble !in minDouble..maxDouble) return false
        valueDouble = (valueDouble + amount).coerceIn(minDouble, maxDouble)
        sliderPercentage = ((valueDouble - minDouble) / (maxDouble - minDouble)).toFloat()
        return true
    }

    override val isHovered: Boolean
        get() =
            isAreaHovered(lastX, lastY + getHeight() / 2, width, getHeight() / 2, true)

    override fun getHeight(): Float = Panel.HEIGHT + 8f

    override fun write(gson: Gson): JsonElement = JsonPrimitive(value)

    override fun read(element: JsonElement, gson: Gson) {
        element.asNumber?.let { value = it as E }
    }

    private fun roundToIncrement(x: Number): Double =
        round((x.toDouble() / incrementDouble)) * incrementDouble

    private fun getDisplay(): String =
        if (valueDouble - floor(valueDouble) == 0.0)
            "${(valueInt * 100.0).roundToInt() / 100}${unit}"
        else
            "${(valueDouble * 100.0).roundToInt() / 100.0}${unit}"
}