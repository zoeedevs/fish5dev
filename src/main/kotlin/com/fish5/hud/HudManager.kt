package com.fish5.hud

/**
 * A draggable on-screen element the unified [HudEditorScreen] can move. Everything is expressed in the
 * NanoVG "standard GUI scale" virtual space (≈1920×1080) that fish5's overlays already use, so one
 * editor can position the wormfish session/lifetime/forge panels.
 *
 * Positions persist through each feature's own [hudX]/[hudY] settings — the editor just mutates them.
 */
interface HudElement {
    /** Short name shown above the element in the editor. */
    val hudLabel: String

    /** Top-left position in NanoVG virtual pixels. Backed by each feature's own persisted settings. */
    var hudX: Int
    var hudY: Int

    /** Current bounds in NanoVG virtual pixels (from the last render). */
    fun hudWidth(): Float
    fun hudHeight(): Float

    /**
     * Draws the element at [hudX]/[hudY]. Called inside an active NanoVG block already scaled by the
     * standard GUI scale. When [editor] is true the element should draw even without live data
     * (placeholder values) so it's visible/positionable in the editor.
     */
    fun renderHudElement(editor: Boolean)
}

/** Registry of all draggable HUD elements, iterated by [HudEditorScreen]. */
object HudManager {
    val elements: MutableList<HudElement> = mutableListOf()

    fun register(element: HudElement) {
        if (!elements.contains(element)) elements.add(element)
    }
}
