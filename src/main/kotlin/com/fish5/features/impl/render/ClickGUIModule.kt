package com.fish5.features.impl.render

import com.fish5.clickgui.settings.impl.BooleanSetting
import com.fish5.clickgui.settings.impl.ColorSetting
import com.fish5.clickgui.settings.impl.KeybindSetting
import com.fish5.clickgui.settings.impl.MapSetting
import com.fish5.clickgui.settings.impl.NumberSetting
import org.lwjgl.glfw.GLFW
import com.fish5.features.Category
import com.fish5.features.Module
import com.fish5.utils.Color
import com.fish5.utils.ui.rendering.GGRenderer
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sin

/**
 * Click GUI theme module — holds the theme/state the GUI reads: accent colour,
 * persisted per-panel positions, and the auto gui-scale helper.
 * update-checker bits are dropped.
 */
object ClickGUIModule : Module(name = "Theme", category = Category.RENDER, description = "Customize the click GUI's appearance.") {

    // ---- Theme page (opened from the player-head button at the bottom of the rail) ----
    // Names changed from the old "Color"/"Background Color"/"Element Color" on purpose: saved config is
    // keyed by setting name, so renaming lets the new defaults apply instead of the old
    // flat-grey values an existing config would otherwise restore.
    private val accentA by ColorSetting("Accent", Color(138, 171, 219), desc = "Accent used for tabs, the active rail marker and highlights.")
    private val accentB by ColorSetting("Accent 2", Color(180, 140, 240), desc = "Second accent, blended in when Gradient accent is on.")
    private val gradientAccent by BooleanSetting("Gradient accent", false, desc = "Smoothly cycle the accent between the two colors.")

    /** The live accent color every GUI element reads — a time-blend of the two colors when Gradient is on. */
    val clickGUIColor: Color get() = if (gradientAccent) lerp(accentA, accentB, wave()) else accentA

    val backgroundColor by ColorSetting("Window Color", Color(30, 31, 34, 0.92f), allowAlpha = true, desc = "The click GUI window background.")
    val cardColor by ColorSetting("Card Color", Color(46, 46, 49, 0.94f), allowAlpha = true, desc = "Module and setting cards.")
    val elementColor by ColorSetting("Control Color", Color(18, 18, 20, 0.88f), allowAlpha = true, desc = "Pills, inputs, slider tracks and toggle tracks.")

    // ---- General > GUI page ----
    val guiKey by KeybindSetting("GUI Keybind", GLFW.GLFW_KEY_RIGHT_SHIFT, desc = "Opens the Fish 5 GUI when pressed.")
    val guiScalePercent by NumberSetting("GUI Scale", 100.0, 60, 150, 5, desc = "Scale of the click GUI.", unit = "%")
    val openAnimation by BooleanSetting("Open Animation", true, desc = "Scale and fade the window in when the GUI opens.")

    // ---- General > Rendering page ----
    val smoothFont by BooleanSetting("Smooth Font", true, desc = "Use the bundled Inter font instead of the Minecraft font.")
    val supersampling by BooleanSetting("Supersampling", true, desc = "Rasterizes every Fish 5 screen and HUD shape on the real screen pixel grid for smoother corners, circles and edges.")

    /** Settings shown on the General page; the rest of this module's visible settings form the Theme page. */
    val GUI_SETTING_NAMES = setOf("GUI Keybind", "GUI Scale", "Supersampling", "Smooth Font", "Open Animation")

    /** The subset of [GUI_SETTING_NAMES] grouped under General > Rendering. */
    val RENDER_SETTING_NAMES = setOf("Smooth Font", "Supersampling")

    /** Layout scale for the click GUI itself: [getStandardGuiScale] times the user's GUI Scale %. */
    fun clickGuiScale(): Float = getStandardGuiScale() * (guiScalePercent.toFloat() / 100f)

    /** What [com.fish5.utils.ui.isAreaHovered] (scaled) divides by: the click GUI's scale while it's open, else the HUD one. */
    fun hoverScale(): Float = if (com.fish5.clickgui.ClickGUI.isOpen) clickGuiScale() else getStandardGuiScale()

    val panelSetting by MapSetting("Panel Settings", mutableMapOf<String, PanelData>())
    data class PanelData(var x: Float = 10f, var y: Float = 10f, var extended: Boolean = true)

    fun resetPositions() {
        Category.categories.entries.forEachIndexed { index, (categoryName, _) ->
            val setting = panelSetting.getOrPut(categoryName) { PanelData() }
            setting.x = 10f + 260f * index
            setting.y = 10f
            setting.extended = true
        }
    }

    fun getStandardGuiScale(): Float {
        // mc.window.screenWidth/screenHeight (Window#getScreenWidth/getScreenHeight) are the raw OS
        // window size -- they do NOT move when the user changes vanilla's GUI Scale option, only when
        // the window/monitor resolution itself changes. But this GUI lays out into
        // context.guiWidth()/guiHeight() (Window#getGuiScaledWidth/getGuiScaledHeight), which IS
        // vanilla-GUI-Scale-adjusted (= framebuffer size / GUI Scale) and shrinks as GUI Scale rises.
        // Returning a resolution-only factor here (no GUI Scale term) meant the content canvas
        // (guiWidth()/scale) shrank with GUI Scale while nothing drawn into it did, so raising GUI
        // Scale blew the whole click GUI up past the edges of its own shrunken canvas instead of
        // leaving it alone. Dividing by the current GUI Scale cancels that out: resolutionScale/scale
        // guiScaledPixels per layout unit * scale physical pixels per guiScaledPixel = resolutionScale
        // physical pixels per layout unit, independent of GUI Scale again -- matching what this was
        // always meant to do.
        val verticalScale = mc.window.screenHeight.toFloat() / 1080f
        val horizontalScale = mc.window.screenWidth.toFloat() / 1920f
        val resolutionScale = round(max(verticalScale, horizontalScale).coerceIn(1f, 3f) * 10f) / 10f
        return resolutionScale / mc.window.guiScale.toFloat().coerceAtLeast(1f)
    }

    /** 0..1 triangle/sine sweep used to animate the gradient accent. */
    private fun wave(): Float = ((sin(System.currentTimeMillis() / 1200.0) + 1.0) / 2.0).toFloat()

    private fun lerp(a: Color, b: Color, t: Float): Color {
        fun c(x: Int, y: Int) = (x + (y - x) * t).toInt().coerceIn(0, 255)
        val alpha = (a.alpha + (b.alpha - a.alpha) * t).coerceIn(0f, 1f)
        return Color(c(a.red, b.red), c(a.green, b.green), c(a.blue, b.blue), alpha)
    }
}
