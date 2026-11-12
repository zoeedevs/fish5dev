package com.fish5.clickgui

import com.fish5.features.impl.render.ClickGUIModule
import com.fish5.utils.ui.rendering.GGRenderer

/**
 * palette + the small shared widgets (toggle switch, pill) every click-GUI element
 * draws with. Colours the user can change live on the Theme page (window / card / control / accent)
 * are read through [ClickGUIModule]; the rest are fixed neutrals tuned for the dark translucent look.
 */
object Theme {

    fun argb(a: Int, r: Int, g: Int, b: Int): Int = (a shl 24) or (r shl 16) or (g shl 8) or b

    val text: Int = argb(255, 255, 255, 255)
    val textSecondary: Int = argb(255, 164, 166, 173)
    val textTertiary: Int = argb(255, 140, 142, 150)
    val divider: Int = argb(24, 255, 255, 255)
    val border: Int = argb(16, 255, 255, 255)
    val railActive: Int = argb(31, 255, 255, 255)
    val railHover: Int = argb(18, 255, 255, 255)
    val tooltip: Int = argb(245, 70, 72, 78)
    val backdrop: Int = argb(90, 0, 0, 0)
    val scrollbar: Int = argb(56, 255, 255, 255)

    // "Clear" switch: neutral grey when on rather than the accent.
    private val toggleOnTrack = argb(255, 108, 109, 114)
    private val toggleOnKnob = argb(255, 220, 221, 226)
    private val toggleOffKnob = argb(255, 90, 91, 96)

    val window: Int get() = ClickGUIModule.backgroundColor.rgba
    val card: Int get() = ClickGUIModule.cardColor.rgba
    val cardHover: Int get() = lighten(card, 0.10f)
    val control: Int get() = ClickGUIModule.elementColor.rgba
    val controlHover: Int get() = lighten(control, 0.10f)
    val accent: Int get() = ClickGUIModule.clickGUIColor.rgba

    const val TOGGLE_W = 46f
    const val TOGGLE_H = 24f

    /** Channel-wise blend of two ARGB colours, [t] in 0..1. */
    fun lerp(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(shift: Int): Int {
            val x = (a ushr shift) and 0xFF
            val y = (b ushr shift) and 0xFF
            return (x + (y - x) * k).toInt().coerceIn(0, 255)
        }
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Moves RGB toward white by [amount], keeping alpha (nudged up slightly so hover reads on glass). */
    fun lighten(c: Int, amount: Float): Int {
        val a = (((c ushr 24) and 0xFF) + 12).coerceAtMost(255)
        return (lerp(c, c or 0x00FFFFFF, amount) and 0x00FFFFFF) or (a shl 24)
    }

    fun withAlpha(c: Int, alpha: Float): Int =
        (c and 0x00FFFFFF) or ((((c ushr 24) and 0xFF) * alpha.coerceIn(0f, 1f)).toInt() shl 24)

    /** [TOGGLE_W]x[TOGGLE_H] switch at ([x],[y]); [progress] 0 = off .. 1 = on (feed it the toggle animation). */
    fun toggle(x: Float, y: Float, progress: Float, hovered: Boolean) {
        val p = progress.coerceIn(0f, 1f)
        val off = if (hovered) controlHover else control
        GGRenderer.rect(x, y, TOGGLE_W, TOGGLE_H, lerp(off, toggleOnTrack, p), TOGGLE_H / 2f)
        GGRenderer.hollowRect(x, y, TOGGLE_W, TOGGLE_H, 1f, border, TOGGLE_H / 2f)
        val knobR = 8f
        val knobX = x + 4f + knobR + p * (TOGGLE_W - 8f - knobR * 2f)
        GGRenderer.circle(knobX, y + TOGGLE_H / 2f, knobR, lerp(toggleOffKnob, toggleOnKnob, p))
    }

    /** Rounded dark pill (keybind chips, value chips, buttons). */
    fun pill(x: Float, y: Float, w: Float, h: Float, hovered: Boolean, radius: Float = h / 2f) {
        GGRenderer.rect(x, y, w, h, if (hovered) controlHover else control, radius)
    }
}
