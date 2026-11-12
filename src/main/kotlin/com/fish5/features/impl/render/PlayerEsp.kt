package com.fish5.features.impl.render

import com.fish5.clickgui.settings.impl.BooleanSetting
import com.fish5.clickgui.settings.impl.ColorSetting
import com.fish5.clickgui.settings.impl.NumberSetting
import com.fish5.clickgui.settings.impl.SelectorSetting
import com.fish5.features.Category
import com.fish5.features.Module
import com.fish5.utils.Color
import net.minecraft.gizmos.GizmoStyle
import net.minecraft.gizmos.Gizmos
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player

/**
 * Player ESP: through-wall highlights for other players.
 *
 *  - **Box**: a 3D outline gizmo around each player, re-emitted every frame from
 *    [com.fish5.mixin.LevelRendererEspMixin] with the render partial tick, so it follows the
 *    interpolated position instead of stuttering at 20 tps.
 *  - **Cham / Glow**: vanilla's entity glow (outline buffer + sobel + blur). Forced on by
 *    [com.fish5.mixin.MinecraftEspGlowMixin] and coloured by
 *    [com.fish5.mixin.EntityEspColorMixin].
 *  - **Cham / Clean**: the same outline buffer, but the post chain is swapped for
 *    `fish5:entity_outline_clean`, which supersamples the silhouette into a thin anti-aliased line
 *    instead of vanilla's blurred halo.
 */
object PlayerEsp : Module(
    name = "Player ESP",
    category = Category.RENDER,
    description = "See other players through walls: a box and/or a cham outline (Clean line or vanilla Glow).",
) {
    const val CHAM_OFF = 0
    const val CHAM_CLEAN = 1
    const val CHAM_GLOW = 2

    private val boxEnabled by BooleanSetting("Box", true, desc = "Draw a 3D box around each player, visible through walls.")
    private val boxWidth by NumberSetting("Box Width", 2f, 1f, 6f, 0.5f, desc = "Line thickness of the box.")
    private val chamMode by SelectorSetting(
        "Cham", "Clean", listOf("Off", "Clean", "Glow"),
        desc = "Clean: thin supersampled outline. Glow: vanilla glowing effect.",
    )
    private val color by ColorSetting("Color", Color(255, 60, 60), desc = "Colour of the box and cham outline.")
    private val showSelf by BooleanSetting("Show Self", false, desc = "Also highlight yourself (cham only shows in third person).")
    private val ignoreNpcs by BooleanSetting("Ignore NPCs", true, desc = "Skip fake-player NPCs (non-v4 UUIDs, e.g. Hypixel NPCs).")
    private val maxDistance by NumberSetting("Max Distance", 0f, 0f, 256f, 8f, desc = "Only highlight players within this many blocks. 0 = unlimited.")

    private val username by BooleanSetting(
        "Username", true,
        desc = "Show the player's username above their head and hide all other player nametags.",
    )

    /** True when vanilla player nametags are replaced by the ESP username label. */
    @JvmStatic
    fun usesUsernameTag(): Boolean = enabled && username

    private val CLEAN_CHAIN = Identifier.fromNamespaceAndPath("fish5", "entity_outline_clean")

    /** True when the cham outline should be drawn with the Clean post chain instead of vanilla's. */
    @JvmStatic
    fun useCleanChain(): Boolean = enabled && chamMode == CHAM_CLEAN

    @JvmStatic
    fun cleanChainId(): Identifier = CLEAN_CHAIN

    /** Whether [e] gets any ESP treatment right now. */
    @JvmStatic
    fun shouldHighlight(e: Entity): Boolean {
        if (!enabled || e !is Player) return false
        val me = mc.player ?: return false
        if (e === me) return showSelf
        if (e.isSpectator || e.isInvisible) return false
        if (ignoreNpcs && e.uuid.version() != 4) return false
        val range = maxDistance
        if (range > 0f && e.distanceToSqr(me) > range * range) return false
        return true
    }

    /** Whether [e] should use the vanilla outline buffer (Clean and Glow both ride on it). */
    @JvmStatic
    fun wantsOutline(e: Entity): Boolean = enabled && chamMode != CHAM_OFF && shouldHighlight(e)

    /** RGB outline colour handed to the vanilla outline renderer (alpha is forced opaque later). */
    @JvmStatic
    fun outlineRgb(): Int = color.rgba and 0xFFFFFF

    /** Called once per rendered frame, before gizmos are finalised. */
    @JvmStatic
    fun emitBoxes(partialTick: Float) {
        if (!enabled || !boxEnabled) return
        val level = mc.level ?: return
        val argb = color.rgba or (0xFF shl 24)
        val style = GizmoStyle.stroke(argb, boxWidth)
        val collection =
            mc.levelRenderer.collectPerFrameRenderThreadGizmos()
        try {
            for (p in level.players()) {
                if (!shouldHighlight(p)) continue
                val shift = p.getPosition(partialTick).subtract(p.position())
                Gizmos.cuboid(p.boundingBox.move(shift), style).setAlwaysOnTop()
            }
        } finally {
            collection.close()
        }
    }
}
