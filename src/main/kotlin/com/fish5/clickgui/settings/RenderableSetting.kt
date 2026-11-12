package com.fish5.clickgui.settings

import com.fish5.clickgui.ClickGUI
import com.fish5.clickgui.Panel
import com.fish5.features.impl.render.ClickGUIModule
import com.fish5.utils.Colors
import com.fish5.utils.ui.HoverHandler
import com.fish5.utils.ui.isAreaHovered
import com.fish5.utils.ui.rendering.GGRenderer
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent

abstract class RenderableSetting<T>(
    name: String,
    description: String
) : Setting<T>(name, description) {

    // Used to be the whole row (hover anywhere on the setting for 750ms) -- that fired constantly
    // just moving the mouse down the settings list. Now only the little [helpRadius] "?" badge next
    // to the name triggers it, so a short delay reads as instant rather than laggy.
    private val helpHover = HoverHandler(150)
    private val helpRadius = 7f

    /**
     * Row width settings lay themselves out against -- every setting type positions its controls as
     * offsets from the left/right edges of this (e.g. `x + width - 40f`), so widening it to fill the
     * app-shell settings page's content column (instead of the old fixed [Panel.WIDTH]) stretches
     * every setting type correctly with no changes needed in the 9 setting-impl classes.
     */
    var width = Panel.WIDTH
    protected var lastX = 0f
    protected var lastY = 0f
    var listening = false

    /** False when the click GUI draws this setting inside a card with its description written out inline. */
    var showHelpBadge = true

    /**
     * True when the click GUI draws this setting's name (and description) itself, in a card
     * with the control vertically centred on the right -- the setting then only draws its control.
     * Only meaningful for settings whose [inlineControlWidth] is > 0.
     */
    var hideName = false

    /**
     * Click GUI layout hint: on a single-section page (a module's settings), span both columns as one
     * long bar instead of taking one masonry slot -- for buttons that would otherwise leave the two
     * columns uneven. Presentation only; not saved.
     */
    var fullWidth = false

    /**
     * Width of this setting's right-hand control when it is a single-row "name ... control" setting
     * (toggle, key chip, dropdown chip, colour swatch). 0 = a full-width setting (slider, text field,
     * button, list) that lays out its own name and must not be reflowed into a card row.
     */
    open fun inlineControlWidth(): Float = 0f

    open fun render(x: Float, y: Float, mouseX: Float, mouseY: Float): Float {
        lastX = x
        lastY = y
        val height = getHeight()

        if (showHelpBadge && description.isNotEmpty()) {
            // Sits right after the name, vertically centered in the fixed-height header band every
            // setting type draws its name/primary control in (the taller types -- ColorSetting,
            // NumberSetting, StringSetting -- only grow *below* this band). Clamped so a long name
            // can't push it into the primary control sitting at the row's right edge.
            val badgeY = y + Panel.HEIGHT / 2f
            val badgeX = (x + 6f + GGRenderer.textWidth(name, 16f) + 8f + helpRadius)
                .coerceAtMost(x + width - 56f)

            helpHover.handle(badgeX - helpRadius, badgeY - helpRadius, helpRadius * 2f, helpRadius * 2f, true)
            val hovered = helpHover.isHovered
            GGRenderer.circle(badgeX, badgeY, helpRadius, if (hovered) ClickGUIModule.clickGUIColor.rgba else ClickGUIModule.elementColor.rgba)
            val glyphWidth = GGRenderer.textWidth("?", 12f)
            GGRenderer.text("?", badgeX - glyphWidth / 2f, badgeY - 6f, 12f, Colors.WHITE.rgba)

            if (helpHover.percent() > 0) ClickGUI.setDescription(description, x + width + 10f, y, helpHover)
        }

        return height
    }

    open fun mouseClicked(mouseX: Float, mouseY: Float, click: MouseButtonEvent): Boolean = false
    open fun mouseReleased(click: MouseButtonEvent) {}
    open fun keyTyped(input: CharacterEvent): Boolean = false
    open fun keyPressed(input: KeyEvent): Boolean = false
    open fun getHeight(): Float = Panel.HEIGHT

    open val isHovered get() = isAreaHovered(lastX, lastY, width, getHeight(), true)
}