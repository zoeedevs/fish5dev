package com.fish5.clickgui.settings.impl

import com.fish5.clickgui.Panel
import com.fish5.clickgui.Theme
import com.fish5.clickgui.settings.RenderableSetting
import com.fish5.clickgui.settings.Saving
import com.fish5.utils.Colors
import com.fish5.utils.ui.HoverHandler
import com.fish5.utils.ui.isAreaHovered
import com.fish5.utils.ui.rendering.GGRenderer
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import net.minecraft.client.input.MouseButtonEvent
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A min-max pair on one slider with two handles (like aether's range slider). Drag the handle nearest
 * the click; the low handle can't pass the high one and the high one can't pass the low one.
 * Saved as `[low, high]`.
 */
class RangeSetting(
    name: String,
    defaultLow: Int,
    defaultHigh: Int,
    private val min: Int,
    private val max: Int,
    private val increment: Int = 1,
    desc: String,
    private val unit: String = "",
) : RenderableSetting<IntRange>(name, desc), Saving {

    override val default: IntRange = defaultLow..defaultHigh

    var low = defaultLow
        private set
    var high = defaultHigh
        private set

    private val handler = HoverHandler(150)
    private var dragging = 0  // 0 none, 1 low handle, 2 high handle
    private var display = ""
    private var displayWidth = -1f

    override var value: IntRange
        get() = low..high
        set(v) { setBoth(v.first, v.last) }

    init {
        setBoth(defaultLow, defaultHigh)
    }

    private fun snap(v: Int) = (((v - min).toDouble() / increment).roundToInt() * increment + min).coerceIn(min, max)

    private fun setBoth(lo: Int, hi: Int) {
        val a = snap(lo)
        val b = snap(hi)
        low = minOf(a, b)
        high = maxOf(a, b)
        refreshDisplay()
    }

    private fun refreshDisplay() {
        display = "$low-$high$unit"
        displayWidth = -1f
    }

    private fun fraction(v: Int) = if (max == min) 0f else (v - min).toFloat() / (max - min)

    override fun render(x: Float, y: Float, mouseX: Float, mouseY: Float): Float {
        super.render(x, y, mouseX, mouseY)
        val height = getHeight()
        val trackX = x + 6f
        val trackW = width - 12f

        handler.handle(x, y + height / 2, width, height / 2, true)

        if (listening && dragging != 0) {
            val t = ((mouseX - trackX) / trackW).coerceIn(0f, 1f)
            val v = snap(min + (t * (max - min)).roundToInt())
            if (dragging == 1) {
                val nv = v.coerceAtMost(high)
                if (nv != low) { low = nv; refreshDisplay() }
            } else {
                val nv = v.coerceAtLeast(low)
                if (nv != high) { high = nv; refreshDisplay() }
            }
        }

        if (displayWidth < 0) displayWidth = GGRenderer.textWidth(display, 16f, GGRenderer.defaultFont)

        GGRenderer.text(name, x + 6f, y + height / 2f - 15f, 16f, Colors.WHITE.rgba, GGRenderer.defaultFont)
        GGRenderer.text(display, x + width - displayWidth - 4f, y + height / 2f - 15f, 16f, Colors.WHITE.rgba, GGRenderer.defaultFont)

        val lowX = trackX + fraction(low) * trackW
        val highX = trackX + fraction(high) * trackW
        GGRenderer.rect(trackX, y + 24f, trackW, 8f, Theme.control, 4f)
        if (highX - lowX >= 1f) GGRenderer.rect(lowX, y + 24f, highX - lowX, 8f, Theme.argb(255, 201, 203, 210), 4f)

        val r = handler.anim.get(7f, 9f, !isHovered)
        GGRenderer.circle(lowX, y + 28f, if (dragging == 1) 9f else r, Colors.WHITE.rgba)
        GGRenderer.circle(highX, y + 28f, if (dragging == 2) 9f else r, Colors.WHITE.rgba)

        return height
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, click: MouseButtonEvent): Boolean {
        if (click.button() != 0 || !isHovered) return false
        val trackX = lastX + 6f
        val trackW = width - 12f
        val lowX = trackX + fraction(low) * trackW
        val highX = trackX + fraction(high) * trackW
        val dLow = abs(mouseX - lowX)
        val dHigh = abs(mouseX - highX)
        // handles on top of each other: take the one on the side being clicked toward
        dragging = when {
            dLow < dHigh -> 1
            dHigh < dLow -> 2
            mouseX < lowX -> 1
            else -> 2
        }
        listening = true
        return true
    }

    override fun mouseReleased(click: MouseButtonEvent) {
        listening = false
        dragging = 0
    }

    override val isHovered: Boolean
        get() = isAreaHovered(lastX, lastY + getHeight() / 2, width, getHeight() / 2, true)

    override fun getHeight(): Float = Panel.HEIGHT + 8f

    override fun write(gson: Gson): JsonElement = JsonArray().apply { add(low); add(high) }

    override fun read(element: JsonElement, gson: Gson) {
        if (!element.isJsonArray) return
        val a = element.asJsonArray
        if (a.size() != 2) return
        setBoth(a[0].asInt, a[1].asInt)
    }
}
