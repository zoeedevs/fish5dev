package com.fish5.notification

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Shows a big centered on-screen title. Drives the vanilla title overlay
 * (`Gui.hud.setTitle/setSubtitle/setTimes`).
 */
object TitleManager {

    private const val TICKS_PER_SECOND = 20.0

    fun sendTitle(
        title: String,
        subtitle: String? = null,
        duration: Duration = 3.seconds,
        fadeIn: Int = 5,
        fadeOut: Int = 10,
    ) {
        val gui = Minecraft.getInstance().gui
        val hud = gui.hud
        val stayTicks = (duration.inWholeMilliseconds / 1000.0 * TICKS_PER_SECOND).toInt().coerceAtLeast(1)

        hud.setTimes(fadeIn, stayTicks, fadeOut)
        hud.setSubtitle(Component.literal(subtitle ?: ""))
        hud.setTitle(Component.literal(title))
    }

    fun clear() {
        val gui = Minecraft.getInstance().gui
        val hud = gui.hud
        hud.setTitle(Component.empty())
        hud.setSubtitle(Component.empty())
        hud.resetTitleTimes()
    }
}
