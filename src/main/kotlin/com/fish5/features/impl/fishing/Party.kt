package com.fish5.features.impl.fishing

import com.fish5.Fish5
import com.fish5.automation.Msg
import com.fish5.sb.SkyblockData
import com.fish5.utils.ClientUtils

/**
 * Who is in our Hypixel party, from `/p list`, plus party chat. Read from chat only, so it works
 * whatever the members run.
 *
 * `/p list` prints:
 * ```
 * Party Members (2)
 * Party Leader: [VIP] The_Hunter1731 ●
 * Party Moderators: [MVP+] Someone ● Other ●
 * Party Members: [VIP] zoee ●
 * ```
 * [refresh] sends it. The list is re-read after anyone joins or leaves (while [autoRefresh] is on).
 * Membership survives world changes (warps), so it's only dropped on a disconnect ([reset]).
 */
object Party {

    private const val COLLECT_MS = 1500L
    private const val MIN_REFRESH_GAP_MS = 8000L
    private const val MAX_MEMBERS = 24

    /** Other party members' names (not us). Empty = not in a party, or not read yet. */
    val members = ArrayList<String>(8)

    /** True once a `/p list` answer (or "not in a party") has been read. */
    var known = false
        private set

    /** Re-read the list on join/leave messages while anyone wants it (the party macro and/or the party HUD). */
    const val WANT_MACRO = 1
    const val WANT_HUD = 2
    private var refreshWanted = 0
    val autoRefresh: Boolean get() = refreshWanted != 0

    fun wantAutoRefresh(source: Int, on: Boolean) {
        refreshWanted = if (on) refreshWanted or source else refreshWanted and source.inv()
    }

    private var collectUntil = 0L
    private var lastRefreshAt = 0L
    private var refreshDueAt = 0L

    /** Called with (sender, message) for each party chat line. */
    var partyChatListener: ((String, String) -> Unit)? = null

    fun refresh() {
        val now = System.currentTimeMillis()
        if (now - lastRefreshAt < MIN_REFRESH_GAP_MS) { refreshDueAt = lastRefreshAt + MIN_REFRESH_GAP_MS; return }
        lastRefreshAt = now
        refreshDueAt = 0L
        ClientUtils.sendCommand("/p list")
    }

    /** Client tick: forgets the party on disconnect, sends a delayed refresh if one is due. */
    fun tick() {
        if (Fish5.mc.connection == null) {
            if (known || members.isNotEmpty()) reset()
            return
        }
        if (refreshDueAt != 0L && System.currentTimeMillis() >= refreshDueAt) refresh()
    }

    fun isMember(name: String): Boolean {
        for (m in members) if (m.equals(name, true)) return true
        return false
    }

    /** Every non-actionbar chat line (raw). */
    fun onChat(raw: String) {
        val text = SkyblockData.stripColors(raw).trim()
        if (text.isEmpty()) return
        val now = System.currentTimeMillis()

        // party chat: "Party > [VIP] name: message"
        if (text.startsWith("Party > ")) {
            val colon = text.indexOf(": ")
            if (colon > 8) {
                val sender = lastWord(text.substring(8, colon))
                partyChatListener?.invoke(sender, text.substring(colon + 2))
            }
            return
        }

        if (text.startsWith("Party Members (")) {
            members.clear()
            known = true
            collectUntil = now + COLLECT_MS
            return
        }
        if (now <= collectUntil) {
            val roleEnd = when {
                text.startsWith("Party Leader: ") -> 14
                text.startsWith("Party Moderators: ") -> 18
                text.startsWith("Party Members: ") -> 15
                else -> -1
            }
            if (roleEnd > 0) {
                addNames(text.substring(roleEnd))
                return
            }
        }

        if (text == "You are not currently in a party." || text.startsWith("The party was disbanded") ||
            text.startsWith("You left the party") || text.startsWith("You have been kicked from the party")
        ) {
            members.clear()
            known = true
            Msg.dbg("Party: not in a party")
            return
        }

        if (autoRefresh && (text.endsWith("joined the party.") || text.endsWith("has left the party.") ||
                text.endsWith("has been removed from the party.") || text.contains("was removed from your party"))
        ) {
            // let the change settle, then re-read (throttled)
            if (refreshDueAt == 0L) refreshDueAt = now + 1500L
        }
    }

    // "[VIP] The_Hunter1731 ● [MVP+] Other ●"
    private fun addNames(list: String) {
        val self = Fish5.mc.player?.name?.string
        for (part in list.split('●')) {
            val p = part.trim()
            if (p.isEmpty()) continue
            val name = lastWord(p)
            if (name.isEmpty() || name.equals(self, true) || isMember(name)) continue
            if (members.size < MAX_MEMBERS) members.add(name)
        }
        Msg.dbg("Party: members $members")
    }

    private fun lastWord(s: String): String {
        val t = s.trim()
        val sp = t.lastIndexOf(' ')
        return if (sp >= 0) t.substring(sp + 1) else t
    }

    fun reset() {
        members.clear()
        known = false
        collectUntil = 0L
        refreshDueAt = 0L
    }
}
