package com.fish5.notification

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/**
 * Sends chat-based notifications. Kept separate from [TitleManager] so callers can pick a title,
 * a notification, or both. Uses `player.sendSystemMessage` (the 26.2 chat API).
 *
 * `sendSystemMessage` can trigger chat-font glyph baking (first use of a given character),
 * which uploads a GL texture and asserts it's on the render thread. Some callers — e.g.
 * [com.fish5.features.impl.skyblock.WormFinder]'s auto-hop loop — run on an
 * [com.fish5.automation.AutoWorker] background thread, so always hop onto the client thread
 * here instead of trusting every call site to already be on it.
 */
object NotificationManager {

    private const val PREFIX = "§b[fish5] §r"

    fun notify(message: String) {
        val mc = Minecraft.getInstance()
        mc.execute {
            val player = mc.player ?: return@execute
            player.sendSystemMessage(Component.literal(PREFIX + message))
        }
    }
}
