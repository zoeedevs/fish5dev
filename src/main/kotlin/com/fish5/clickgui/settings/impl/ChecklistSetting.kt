package com.fish5.clickgui.settings.impl

import com.fish5.clickgui.Theme
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.fish5.clickgui.ClickGUI.gray38
import com.fish5.clickgui.Panel
import com.fish5.clickgui.settings.RenderableSetting
import com.fish5.clickgui.settings.Saving
import com.fish5.features.impl.render.ClickGUIModule
import com.fish5.utils.Colors
import com.fish5.utils.ui.animations.EaseInOutAnimation
import com.fish5.utils.ui.isAreaHovered
import com.fish5.utils.ui.rendering.GGRenderer
import net.minecraft.client.input.MouseButtonEvent

/**
 * Expandable checklist of runtime-supplied string items, modelled on [SelectorSetting]'s dropdown.
 * The displayed [items] are set by the owner (e.g. a mods-folder scan); the checked subset is the
 * persisted [value]. Used by the Proxy Manager to pick which installed mods should suspend proxying.
 */
class ChecklistSetting(
    name: String,
    desc: String,
    private val emptyLabel: String = "Nothing to show",
) : RenderableSetting<MutableSet<String>>(name, desc), Saving {

    override val default: MutableSet<String> = linkedSetOf()
    override var value: MutableSet<String> = linkedSetOf()

    /** Items to display; refreshed by the owner. Not persisted (the checked [value] is). */
    var items: List<String> = emptyList()

    /** Optional lazy populator, invoked the first time the list is expanded while empty. */
    var refresher: (() -> Unit)? = null

    private val anim = EaseInOutAnimation(200)
    private var extended = false
    private val defaultHeight = Panel.HEIGHT
    private val rowH = 24f

    private fun rowCount(): Int = maxOf(1, items.size)

    override fun render(x: Float, y: Float, mouseX: Float, mouseY: Float): Float {
        super.render(x, y, mouseX, mouseY)

        GGRenderer.text("$name (${value.size})", x + 6f, y + defaultHeight / 2f - 8f, 16f, Colors.WHITE.rgba, GGRenderer.defaultFont)

        val box = 16f
        val bx = x + width - 6f - box
        val by = y + defaultHeight / 2f - box / 2f
        GGRenderer.rect(bx, by, box, box, gray38.rgba, 4f)
        GGRenderer.hollowRect(bx, by, box, box, 1.5f, Theme.border, 4f)
        GGRenderer.text(if (extended) "–" else "+", bx + box / 2f - 3f, by + 1f, 15f, Colors.WHITE.rgba, GGRenderer.defaultFont)

        if (!extended && !anim.isAnimating()) return defaultHeight

        val displayHeight = getHeight()
        if (anim.isAnimating()) GGRenderer.pushScissor(x, y, width, displayHeight)

        GGRenderer.rect(x + 6, y + defaultHeight + 1f, width - 12f, rowCount() * rowH, gray38.rgba, 5f)

        if (items.isEmpty()) {
            GGRenderer.text(emptyLabel, x + 16f, y + defaultHeight + rowH / 2f - 7f, 14f, Colors.MINECRAFT_GRAY.rgba, GGRenderer.defaultFont)
        } else {
            for (i in items.indices) {
                val rowY = y + defaultHeight + i * rowH
                val checked = value.contains(items[i])
                val cb = 13f
                val cbx = x + 14f
                val cby = rowY + rowH / 2f - cb / 2f
                GGRenderer.rect(cbx, cby, cb, cb, (if (checked) ClickGUIModule.clickGUIColor else gray38).rgba, 3f)
                GGRenderer.hollowRect(cbx, cby, cb, cb, 1.5f, Theme.border, 3f)
                GGRenderer.text(items[i], cbx + cb + 8f, rowY + rowH / 2f - 7f, 14f, Colors.WHITE.rgba, GGRenderer.defaultFont)
                if (isRowHovered(i)) GGRenderer.hollowRect(x + 6, rowY, width - 12f, rowH, 1.5f, Theme.border, 4f)
            }
        }

        if (anim.isAnimating()) GGRenderer.popScissor()
        return displayHeight
    }

    private fun isRowHovered(i: Int): Boolean =
        isAreaHovered(lastX + 6, lastY + defaultHeight + i * rowH, width - 12f, rowH, true)

    override fun mouseClicked(mouseX: Float, mouseY: Float, click: MouseButtonEvent): Boolean {
        if (click.button() != 0) return false
        if (isHovered) {
            anim.start()
            extended = !extended
            if (extended && items.isEmpty()) refresher?.invoke()
            return true
        }
        if (!extended || items.isEmpty()) return false
        for (i in items.indices) {
            if (isRowHovered(i)) {
                val item = items[i]
                if (!value.remove(item)) value.add(item)
                return true
            }
        }
        return false
    }

    override val isHovered: Boolean get() = isAreaHovered(lastX, lastY, width, defaultHeight, true)

    override fun getHeight(): Float =
        anim.get(defaultHeight, defaultHeight + rowCount() * rowH, !extended)

    override fun write(gson: Gson): JsonElement {
        val arr = JsonArray()
        for (s in value) arr.add(s)
        return arr
    }

    override fun read(element: JsonElement, gson: Gson) {
        element.asJsonArray?.let {
            value.clear()
            for (e in it) value.add(e.asString)
        }
    }
}
