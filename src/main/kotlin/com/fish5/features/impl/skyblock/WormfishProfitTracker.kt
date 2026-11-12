package com.fish5.features.impl.skyblock

import com.fish5.features.impl.fishing.WormFishing
import com.fish5.Fish5
import com.fish5.automation.BazaarQuotes
import com.fish5.automation.Msg
import com.fish5.features.ModuleManager
import com.fish5.notification.NotificationManager
import com.fish5.sb.HypixelApi
import com.fish5.sb.NpcPrices
import com.fish5.sb.SkyblockData
import com.fish5.sb.MarketPrices
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.world.entity.player.Player
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live coins/hr for Crystal Hollows worm fishing ([WormFinder]'s companion feature) -- valuing drops
 * the way they're actually realised, not at a naive "sell the raw drop" price:
 *
 *  - **Eternal Flame Ring** is valued at its fixed NPC sell price (10,000 -- it isn't bazaar-tradeable
 *    and a guaranteed NPC price beats the hassle of AH-listing a common uncommon-tier drop).
 *  - **Worm Membrane** is valued as if every 100 of them were forged into a **Gemstone Chamber**
 *    (the actual Forge recipe: 100 Worm Membrane + 1 Gemstone Mixture + 25,000 coins) and the chamber
 *    sold at its 7-day AH average, minus the Gemstone Mixture's live bazaar cost and the forge fee.
 *    That profit is spread evenly per membrane (chamberProfit / 100) so the live rate updates smoothly
 *    catch-by-catch instead of jumping once every 100 membranes.
 *
 * Catch detection is two different mechanisms because the two drops behave differently in SkyBlock:
 *  - The ring sits in your physical inventory, so a tick-to-tick inventory scan (summing matching
 *    stacks, crediting only increases) catches it -- the standard live-tracker approach.
 *  - Worm Membrane, like most fishing-sack materials, flies straight into your **Fishing Sack** and
 *    never touches the inventory once that sack is unlocked. Fishing Rods don't carry a
 *    catch counter, but Hypixel *does* send a `[Sacks]` chat line on every sack change,
 *    whose hover text carries the real breakdown (e.g. "+5 Worm Membrane (Fishing Sack)"). We parse
 *    that instead of polling an API, so live tracking needs no Hypixel API key at all. An API key
 *    ([WormHelper.apiKey]) is only used once, on
 *    enable, to seed an accurate *starting* sack count for `/f5 wormhelper calc` -- without one, tracking still
 *    works, it just starts counting from the next catch instead of your pre-existing sack contents.
 *    Requires Hypixel's own "Sacks" chat notifications to be on (default on; `/sbmenu` can toggle it).
 *
 * Deliberately does NOT model Forge slot count / Quick Forge speed as a throughput cap -- it's a "what
 * are you actually catching worth" rate, not a "how fast can you realise it" one.
 *
 * The session clock ([sessionOnlineMs]) only advances while [activelyWormFishing] -- Crystal Hollows
 * ([WormFinder.inCrystalHollows]) plus a recent "Fishing (x/y)" action-bar XP sighting, re-sampled every
 * 10s by [updateActivityCheck], so AFK/menu/elsewhere time doesn't dilute coins/hr.
 */
object WormfishProfitTracker {

    private val mc get() = Fish5.mc

    // Tracking (session/lifetime stats + HUD panels) runs while the combined Worm Helper module is on;
    // every persisted value lives on that module so it all saves under one config entry.
    private val enabled get() = WormHelper.enabled

    private var hudRegistered = false

    /** Registers the chat watchers. Call once from [Fish5] init. The panels (session stats, forge timer)
     * live in the Macro HUD ([com.fish5.hud.MacroHud]). */
    fun initHud() {
        if (hudRegistered) return
        hudRegistered = true

        // Read-only action-bar watch (GAME, not ALLOW_GAME/MODIFY_GAME -- this never filters or
        // rewrites the message, just observes it) so the session clock below only runs while actively
        // fishing, gated on real Fishing-XP gain instead
        // of raw "online on the island" time.
        ClientReceiveMessageEvents.GAME.register(ClientReceiveMessageEvents.Game { message, overlay -> onGameMessage(message, overlay) })

        // Live Worm Membrane detection -- see the class doc comment. Also read-only/non-filtering.
        ClientReceiveMessageEvents.GAME.register(ClientReceiveMessageEvents.Game { message, overlay -> onSacksChatMessage(message, overlay) })
    }

    // ================================================================================
    //  "Actively worm fishing" gate -- in the Crystal Hollows AND a recent Fishing-XP sighting (Hypixel
    //  briefly shows a "Fishing (x/y)" XP fraction in the action bar after each catch, then reverts to
    //  health/mana a few seconds later). Re-sampled every 10s rather than every tick -- the Crystal
    //  Hollows half of this scans sidebar/tab-list text (see WormFinder.inCrystalHollows), which is
    //  wasted work done 20x/sec for a fact that doesn't change tick-to-tick. Gates the session clock so
    //  AFK/menu/elsewhere-on-the-island time doesn't dilute coins/hr, gated like the session clock
    //  farming session time off real Farming-XP gain instead of raw "online" time.
    // ================================================================================

    // Keyword + num/num shape, not an exact icon/bracket match -- StatusBarTracker hit the same issue
    // with Hypixel's skill-icon glyphs varying and breaking an exact-format regex.
    // Accepts both "(cur/needed)" and the "(12.3%)" form Hypixel degrades to at high skill levels (see
    // the x/y-only version never matched a high-level fisher.
    private val fishingXpPattern = Regex("Fishing.{0,6}[\\d,.]+\\s*(?:/\\s*[\\d,.]+|%)", RegexOption.IGNORE_CASE)
    @Volatile private var lastFishingXpAtMs = 0L
    // Last raw action-bar text seen, for /f5 wormhelper debug -- shows what Hypixel actually sends.
    @Volatile private var lastOverlayText = ""

    // Re-check cadence for [inCrystalHollowsNow]/[activelyWormFishing] -- also doubles as "how stale a
    // Fishing-XP sighting can be and still count", so any sighting inside this window survives until
    // the next sample.
    private const val ACTIVITY_CHECK_INTERVAL_MS = 10_000L
    // Once started (first worm reeled in / Fishing XP in the Hollows), only pause after this long
    // without another one.
    private const val PAUSE_AFTER_NO_XP_MS = 45_000L
    private var lastActivityCheckMs = 0L

    // Whether the HUD panels should show at all -- see initHud's render gating. Separate from
    // [activelyWormFishing] (which additionally requires a recent Fishing-XP sighting): being in the
    // Crystal Hollows but between catches still shows the panels, just marked "(Paused)".
    private var inCrystalHollowsNow = false
    private var activelyWormFishing = false

    // Chat line Hypixel prints every time a worm is reeled in ("A Flaming Worm surfaces from the
    // depths!" for lava; water pools say "A Worm surfaces..."). The most direct "worm fishing" signal
    // there is -- the Fishing-XP action-bar match above is kept as a second signal, either one counts.
    private val wormSurfacePattern = Regex("""A (?:\w+ )*Worm surfaces from the depths!""", RegexOption.IGNORE_CASE)

    private fun onGameMessage(message: Component, overlay: Boolean) {
        val text = message.string
        val matched = if (overlay) {
            lastOverlayText = text
            fishingXpPattern.containsMatchIn(text)
        } else {
            wormSurfacePattern.containsMatchIn(text)
        }
        if (!matched) return
        lastFishingXpAtMs = System.currentTimeMillis()
        // Start/resume immediately on a catch/XP gain instead of waiting for the next 10s sample.
        if (inCrystalHollowsNow) activelyWormFishing = true
    }

    /** Re-samples [inCrystalHollowsNow]/[activelyWormFishing] at most once every
     * [ACTIVITY_CHECK_INTERVAL_MS]; a no-op call in between just reuses the last verdict. */
    private fun updateActivityCheck(now: Long) {
        if (now - lastActivityCheckMs < ACTIVITY_CHECK_INTERVAL_MS) return
        lastActivityCheckMs = now
        inCrystalHollowsNow = WormFinder.inCrystalHollows()
        activelyWormFishing = inCrystalHollowsNow && now - lastFishingXpAtMs <= PAUSE_AFTER_NO_XP_MS
    }

    // ================================================================================
    //  Worm Membrane detection via the "[Sacks]" chat line's hover text -- see the class doc comment.
    //  No inventory slot and no API poll involved; this is the actual live source.
    // ================================================================================

    // Sack change line pattern: " +5 Worm Membrane (Fishing Sack)" / " -100 Worm
    // Membrane (Fishing Sack)" (forging consumes 100 at once). Hypixel can list several items in one
    // hover block (one sack-changing action can touch more than one material), one per line.
    private val sackChangePattern = Regex("""\s*(?<delta>[+-][\d,]+)\s+(?<item>.+?)\s+\((?<sacks>.+)\)""")

    private fun onSacksChatMessage(message: Component, overlay: Boolean) {
        // The "[Sacks]" line itself is a plain system/game chat message, not an action-bar overlay.
        if (overlay || !message.string.startsWith("[Sacks]")) return
        // Hypixel puts the same hover on several parts of the [Sacks] line; read each distinct hover once
        // per message or every delta is counted once per styled part (this doubled the session totals).
        val seenHovers = HashSet<String>()
        for (sibling in message.siblings) {
            val hoverText = (sibling.style.hoverEvent as? HoverEvent.ShowText)?.value?.string ?: continue
            if (!seenHovers.add(hoverText)) continue
            for (match in sackChangePattern.findAll(hoverText)) {
                if (match.groups["item"]?.value?.trim() != "Worm Membrane") continue
                if (match.groups["sacks"]?.value?.contains("Fishing Sack") != true) continue
                val delta = match.groups["delta"]?.value?.replace(",", "")?.toLongOrNull() ?: continue
                onMembraneSackDelta(delta)
            }
        }
    }

    /** Updates the real running sack count (used by [wormCalc]) off every observed delta regardless of
     * [enabled] -- it's bookkeeping of an objective fact about the account, not a session stat -- but
     * only credits session/lifetime profit while actively tracking, and only for gains (forging a
     * chamber fires a matching negative delta here; that's not a loss, it's already been valued at
     * catch time, so it only needs to adjust the running total, not subtract profit). */
    private fun onMembraneSackDelta(delta: Long) {
        lastMembraneSack = ((if (lastMembraneSack < 0) 0L else lastMembraneSack) + delta).coerceAtLeast(0L)
        // the Macro HUD counts drops while a macro runs, whether or not Worm Helper is on
        if (delta > 0) com.fish5.features.MacroSession.addLoot("Worm Membrane", delta, delta * perMembraneValue.coerceAtLeast(0.0))
        if (!enabled || delta <= 0) return

        membranesCaught += delta
        lifetimeMembranes = (lifetimeMembranes.toLong() + delta).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val perEach = perMembraneValue
        if (perEach > 0) {
            val coins = delta * perEach
            sessionCoins += coins
            lifetimeCoins += coins
        }
        ModuleManager.saveConfigurations()
    }

    private val priceRefreshMin get() = WormHelper.priceRefreshMin

    /** `/f5 wormhelper resetlifetime` and the GUI's Reset Lifetime button both go through here. Also
     * resets the session, then re-seeds lifetime membranes from the live sack. */
    fun resetLifetimeCommand() {
        lifetimeCoins = 0.0
        lifetimeRings = 0
        lifetimeMembranes = 0
        resetSession()
        ModuleManager.saveConfigurations()
        NotificationManager.notify("§b[Wormfish] §7Lifetime + session stats reset.")
        seedFromSack()
    }

    /** Reads what's actually in the Fishing Sack now (API, falling back to the tracked count) and makes
     * that both the new lifetime-membrane baseline and the live sack count. */
    private fun seedFromSack() {
        val player = mc.player ?: return
        val key = HARDCODED_API_KEY.ifBlank { WormHelper.apiKey }
        if (key.isBlank()) { applySackSeed(null); return }
        val uuid = player.uuid.toString().replace("-", "")
        Thread({
            val sacks = HypixelApi.sackCounts(uuid, key, true)?.let { it[MEMBRANE_ID] ?: 0L }
            mc.execute { applySackSeed(sacks) }
        }, "fish5-wormfish-lifetime-seed").apply { isDaemon = true }.start()
    }

    private fun applySackSeed(apiSacks: Long?) {
        val tracked = lastMembraneSack.takeIf { it >= 0 }
        // Same rule as wormCalc: the API can lag/omit the entry; if it says 0 but live tracking saw more, trust the larger.
        val n = if (apiSacks != null && apiSacks == 0L && tracked != null && tracked > 0) tracked else apiSacks ?: tracked
        if (n == null) {
            Msg.chat("§cCouldn't read your sack count — lifetime membranes start at 0. Run §f/f5 wormhelper calc §cto sync it.")
            return
        }
        lastMembraneSack = n
        lifetimeMembranes = n.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        ModuleManager.saveConfigurations()
        Msg.chat("§b[Wormfish] §7Lifetime membranes restarted from sack: §f$n")
    }

    /** `/f5 wormhelper reset` and the GUI's Reset Session button both go through here. */
    fun resetSessionCommand() {
        resetSession()
        NotificationManager.notify("§b[Wormfish] §7Session reset.")
    }

    // ================================================================================
    //  Static Forge economics (SkyBlock game-design constants, not live data -- same category as the
    //  hardcoded recipes already in recipes.json; these only change with a content update)
    // ================================================================================

    const val MEMBRANE_PER_CHAMBER = 100
    const val FORGE_COST = 25_000.0

    private const val RING_ID = "ETERNAL_FLAME_RING"
    const val MEMBRANE_ID = "WORM_MEMBRANE"
    const val MIXTURE_ID = "GEMSTONE_MIXTURE"
    const val CHAMBER_ID = "GEMSTONE_CHAMBER"

    // ================================================================================
    //  Live prices (refreshed off-thread, read from the render/tick thread)
    // ================================================================================

    @Volatile private var ringNpcPrice = -1.0
    @Volatile private var chamberAhAvg = -1.0
    @Volatile private var mixtureCost = -1.0
    // Extra prices only /f5 wormhelper calc shows: the mixture's 7d bazaar average, and the chamber's current
    // cheapest active BIN (the 7d avg itself is of actually-sold auctions, via chamberAhAvg).
    @Volatile private var mixtureWeekAvg = -1.0
    @Volatile private var chamberLowestBin = -1.0
    @Volatile private var pricesFetchedAt = 0L
    private val fetchingPrices = AtomicBoolean(false)

    /** Net coins from forging+selling one chamber, or -1 if prices aren't loaded yet. */
    private val chamberProfit: Double
        get() = if (chamberAhAvg < 0 || mixtureCost < 0) -1.0 else chamberAhAvg - mixtureCost - FORGE_COST

    /** Expected-value coins per single Worm Membrane caught (chamber profit spread over the batch). */
    private val perMembraneValue: Double
        get() = chamberProfit.let { if (it < 0) -1.0 else it / MEMBRANE_PER_CHAMBER }

    private fun refreshPrices() {
        if (!fetchingPrices.compareAndSet(false, true)) return
        Thread({
            try {
                if (NpcPrices.isStale()) NpcPrices.fetch()
                ringNpcPrice = NpcPrices.current()?.npcSellPrice(RING_ID)?.toDouble()?.takeIf { it > 0 } ?: -1.0

                if (BazaarQuotes.current() == null) BazaarQuotes.fetch()
                mixtureCost = BazaarQuotes.current()?.quote(MIXTURE_ID)?.instaBuy?.takeIf { it > 0 } ?: -1.0

                chamberAhAvg = MarketPrices.ahWeekAvg(CHAMBER_ID)
                // offerSide=true -> Coflnet "buy", i.e. the insta-buy price, matching mixtureCost above.
                mixtureWeekAvg = MarketPrices.bazaarWeekAvg(MIXTURE_ID, true)
                chamberLowestBin = MarketPrices.cheapestBins(CHAMBER_ID, 1)?.cheapest()?.takeIf { it > 0 } ?: -1.0

                pricesFetchedAt = System.currentTimeMillis()
            } catch (ex: Exception) {
                Msg.LOG.warn("[Wormfish] price refresh failed: {}", ex.toString())
            } finally {
                fetchingPrices.set(false)
            }
        }, "fish5-wormfish-prices").apply { isDaemon = true }.start()
    }

    // ================================================================================
    //  Session state + catch detection
    // ================================================================================

    private var ringsCaught = 0
    private var membranesCaught = 0L
    private var sessionCoins = 0.0
    private var sessionOnlineMs = 0L
    private var lastTickMs = 0L

    // All-time totals, persisted as hidden settings on WormHelper; never touched by resetSession().
    private var lifetimeCoins: Double
        get() = WormHelper.lifetimeCoins
        set(value) { WormHelper.lifetimeCoins = value }
    private var lifetimeRings: Int
        get() = WormHelper.lifetimeRings
        set(value) { WormHelper.lifetimeRings = value }
    private var lifetimeMembranes: Int
        get() = WormHelper.lifetimeMembranes
        set(value) { WormHelper.lifetimeMembranes = value }

    /** -1 = not sampled/observed yet (so the very first read after enabling never looks like a
     * "catch", and [wormCalc] knows to say "no data yet" instead of "0"). Deliberately NOT reset by
     * [resetSession]/[onDisable] -- it tracks an objective fact about your account (what's actually in
     * your sack right now), independent of the session stats around it, and should survive toggling
     * the module or resetting the session just like [lifetimeCoins] does. */
    private var lastRingCount = -1
    private var lastMembraneSack = -1L

    private fun resetSession() {
        ringsCaught = 0
        membranesCaught = 0
        sessionCoins = 0.0
        sessionOnlineMs = 0L
        lastRingCount = -1
    }

    fun onEnabled() {
        resetSession()
        lastTickMs = System.currentTimeMillis()
        refreshPrices()
        bootstrapSackBaseline()
    }

    fun onDisabled() {
        lastRingCount = -1
    }

    /** One-time (per enable) seed of [lastMembraneSack] from Hypixel's own API, if a key is set --
     * only runs when we don't already have a real count (from a past bootstrap or an already-observed
     * `[Sacks]` delta), and only ever moves it in the direction of more accurate, never overwrites live
     * data with a stale read. Purely an accuracy nice-to-have for [wormCalc]'s starting number; catch
     * tracking itself ([onMembraneSackDelta]) never needs this or any further polling. */
    private val bootstrapInFlight = AtomicBoolean(false)
    private var lastBootstrapAttemptMs = 0L

    private fun bootstrapSackBaseline() {
        if (lastMembraneSack >= 0) return
        val apiKey = HARDCODED_API_KEY.ifBlank { WormHelper.apiKey }
        if (apiKey.isBlank()) return
        // onEnable usually runs at config load, before a player exists -- tick() retries until it sticks.
        val player = mc.player ?: return
        if (!bootstrapInFlight.compareAndSet(false, true)) return
        val uuid = player.uuid.toString().replace("-", "")
        Thread({
            try {
                val counts = HypixelApi.sackCounts(uuid, apiKey) ?: return@Thread
                val current = counts[MEMBRANE_ID] ?: 0L
                mc.execute { if (lastMembraneSack < 0) lastMembraneSack = current }
            } finally {
                bootstrapInFlight.set(false)
            }
        }, "fish5-wormfish-sack-bootstrap").apply { isDaemon = true }.start()
    }

    /** Called every client tick from [com.fish5.Fish5]. No-op unless enabled. */
    fun tick() {
        forgeTick() // independent of the module's enabled state
        // Worm Fishing's Macro HUD profit uses the same valuation, so prices + ring catches also run while
        // that macro is on, even with Worm Helper off. Session/lifetime stats stay Worm Helper-only.
        val macroOn = com.fish5.features.impl.fishing.WormFishing.running
        if (!enabled && !macroOn) {
            lastRingCount = -1
            return
        }
        val now = System.currentTimeMillis()
        val delta = (now - lastTickMs).coerceIn(0, 5_000)
        lastTickMs = now

        val player = mc.player
        if (player == null || !SkyblockData.isOnSkyblock(mc)) {
            // Leaving SkyBlock entirely should hide the HUD immediately, not linger on a stale
            // verdict until the next 10s sample.
            inCrystalHollowsNow = false
            activelyWormFishing = false
            return
        }
        if (enabled) {
            updateActivityCheck(now)
            // No sack count yet this launch (module enabled before the world loaded): retry every 30s.
            if (lastMembraneSack < 0 && now - lastBootstrapAttemptMs > 30_000L) {
                lastBootstrapAttemptMs = now
                bootstrapSackBaseline()
            }
            // Only while actively worm fishing (Crystal Hollows + recent Fishing XP, re-sampled every 10s
            // -- see updateActivityCheck) -- not just "online on the island" -- so standing around/AFK/
            // menus/elsewhere doesn't dilute coins/hr.
            if (activelyWormFishing) sessionOnlineMs += delta
        }

        detectRingCatches(player)

        if (now - pricesFetchedAt > priceRefreshMin * 60_000L) refreshPrices()
    }

    private fun detectRingCatches(player: Player) {
        var count = 0
        val inv = player.inventory
        for (i in 0 until 36) {
            val stack = inv.getItem(i)
            if (stack.isEmpty) continue
            if (SkyblockData.skyblockId(stack) == RING_ID) count += stack.count
        }
        if (lastRingCount >= 0 && count > lastRingCount) {
            val gained = count - lastRingCount
            com.fish5.features.MacroSession.addLoot("Eternal Flame Ring", gained.toLong(), gained * ringNpcPrice.coerceAtLeast(0.0))
            if (enabled) {
                ringsCaught += gained
                lifetimeRings += gained
                if (ringNpcPrice > 0) {
                    val coins = gained * ringNpcPrice
                    sessionCoins += coins
                    lifetimeCoins += coins
                }
                ModuleManager.saveConfigurations()
            }
        }
        lastRingCount = count
    }

    private fun membranesPerHour(): Double {
        val hrs = sessionOnlineMs / 3_600_000.0
        return if (hrs <= 0.0) 0.0 else membranesCaught / hrs
    }

    private fun coinsPerHour(): Double {
        val hrs = sessionOnlineMs / 3_600_000.0
        return if (hrs <= 0.0) 0.0 else sessionCoins / hrs
    }

    // ================================================================================
    //  Chat report (/f5 wormhelper report)
    // ================================================================================

    fun report() {
        if (!enabled) {
            Msg.chat("§cEnable the Worm Helper module first.")
            return
        }
        Msg.chat("§6§lWormfish Profit §r§7— session (${fmtDur(sessionOnlineMs)} actively fishing)")
        Msg.chat("§8• §7Rings: §f$ringsCaught §7@ ${priceOrDash(ringNpcPrice)} NPC each")
        Msg.chat(
            "§8• §7Membranes: §f$membranesCaught §7@ ${priceOrDash(perMembraneValue)} each " +
                "§8(chamber ${priceOrDash(chamberAhAvg)} §8- mixture ${priceOrDash(mixtureCost)} §8- forge ${short(FORGE_COST)} §8÷ 100)",
        )
        Msg.chat("§8• §7Session: §f${short(sessionCoins)} §7(§a${signed(coinsPerHour())}/hr§7)")
        Msg.chat("§8• §7Lifetime: §f${short(lifetimeCoins)} §7($lifetimeRings rings, $lifetimeMembranes membranes)")
        Msg.chat("§7Live totals are also on the §bWormfish Session§7/§bLifetime§7 HUD panels — drag them in the HUD editor.")
    }

    private fun priceOrDash(v: Double): String = if (v > 0) short(v) else "§8?"

    /** `/f5 wormhelper debug` -- dumps the raw inputs to the "actively worm fishing" gate so a false
     * negative (not activating despite real XP gain) can actually be diagnosed instead of guessed at:
     * is [WormFinder.inCrystalHollows] seeing the area text it expects, and is the action-bar
     * Fishing-XP regex matching what Hypixel is actually sending. */
    fun debug() {
        val now = System.currentTimeMillis()
        Msg.chat("§6§lWorm Calc Debug")
        Msg.chat("§8• §7inCrystalHollows(): §f${WormFinder.inCrystalHollows()} §7(cached inCrystalHollowsNow: §f$inCrystalHollowsNow§7)")
        Msg.chat(
            "§8• §7Last Fishing-XP action-bar match: §f" +
                if (lastFishingXpAtMs == 0L) "never" else "${now - lastFishingXpAtMs}ms ago",
        )
        Msg.chat("§8• §7activelyWormFishing: §f$activelyWormFishing §7(re-sampled every 10s, not live)")
        Msg.chat("§8• §7Last action-bar text seen: §f'$lastOverlayText'")
        Msg.chat("§8• §7Sidebar lines: §f${SkyblockData.getSidebarLines(mc)}")
        val tab = SkyblockData.getTabListLines(mc)
        Msg.chat("§8• §7Tab-list 'Area' lines: §f${tab.filter { it.contains("Area", ignoreCase = true) }}")
        Msg.chat("§8• §7Tab-list lines (first 10): §f${tab.take(10)}")
    }

    // ================================================================================
    //  /f5 wormhelper calc — one-shot calculator: if we forged every membrane in the sack *right now*
    // ================================================================================

    // Gemstone Chamber base Forge time, confirmed in-game (2026-11-02) -- NOT the same as the generic
    // SkyBlock Wiki Forge-item default, so don't "fix" this back to a guessed value later.
    private const val CHAMBER_BASE_FORGE_MS = 4 * 3_600_000L
    // Quick Forge level 20 (max level) = -30% Forge time, confirmed in-game (2026-11-02).
    private const val QUICK_FORGE_20_MULTIPLIER = 0.7
    const val FORGE_SLOTS = 7
    // Hardcoded Hypixel API key for /f5 wormhelper calc's exact sack count (falls back to the Worm Helper
    // "Hypixel API key" setting if blank). Visible in source/jar -- keep the repo private.
    private const val HARDCODED_API_KEY = "03ae9b4a-18bc-466d-9f97-a89417e5a2b4"

    /** `/f5 wormhelper calc` -- uses the live sack count ([lastMembraneSack]), not the session/lifetime
     * "caught" counters (those only ever go up; the sack count drops once you actually forge). */
    fun wormCalc() {
        val player = mc.player ?: return
        if (chamberAhAvg < 0 || mixtureCost < 0) refreshPrices()

        // Inventory count must be read on the client thread; the sack count comes from the API.
        val inventory = countInventoryMembranes()
        val uuid = player.uuid.toString().replace("-", "")
        val key = HARDCODED_API_KEY.ifBlank { WormHelper.apiKey }
        Msg.chat("§7Fetching exact membrane count (sacks + inventory)…")
        Thread({
            val sacks = if (key.isBlank()) null else HypixelApi.sackCounts(uuid, key, true)?.let { it[MEMBRANE_ID] ?: 0L }
            mc.execute { runWormCalc(sacks, inventory) }
        }, "fish5-wormcalc").apply { isDaemon = true }.start()
    }

    /** Sack counts for [WormForge], fetched on the calling (worker) thread. Membranes fall back to the
     * live-tracked count when the API is unavailable (null = unknown); sacked Gemstone Mixtures fall back to 0. */
    fun fetchSackCountsBlocking(): Pair<Long?, Long> {
        val player = mc.player ?: return lastMembraneSack.takeIf { it >= 0 } to 0L
        val key = HARDCODED_API_KEY.ifBlank { WormHelper.apiKey }
        val tracked = lastMembraneSack.takeIf { it >= 0 }
        if (key.isBlank()) return tracked to 0L
        val counts = HypixelApi.sackCounts(player.uuid.toString().replace("-", ""), key, true) ?: return tracked to 0L
        val api = counts[MEMBRANE_ID] ?: 0L
        // same rule as wormCalc: the API can lag/omit the entry; trust live tracking if it saw more.
        val membranes = if (api == 0L && tracked != null && tracked > 0) tracked else api
        mc.execute { lastMembraneSack = membranes }
        return membranes to (counts[MIXTURE_ID] ?: 0L)
    }

    /** Worm Membranes physically in the player's inventory (stack sizes summed). */
    private fun countInventoryMembranes(): Long {
        val inv = mc.player?.inventory ?: return 0L
        var total = 0L
        for (i in 0 until inv.containerSize) {
            val stack = inv.getItem(i)
            if (!stack.isEmpty && SkyblockData.skyblockId(stack) == MEMBRANE_ID) total += stack.count
        }
        return total
    }

    private fun runWormCalc(apiSacks: Long?, inventory: Long) {
        // API sack count is exact; if it failed, fall back to the live-tracked sack count.
        // The API can lag/omit the entry; if it says 0 but live tracking saw more, trust the larger.
        val tracked = lastMembraneSack.takeIf { it >= 0 }
        val sacks = if (apiSacks != null && apiSacks == 0L && tracked != null && tracked > 0) tracked else apiSacks ?: tracked
        if (sacks == null) {
            Msg.chat("§cCouldn't read your sack count (API failed) and nothing is tracked yet — try again.")
            return
        }
        if (apiSacks != null) lastMembraneSack = sacks else Msg.chat("§eAPI unavailable — using tracked sack count (may be off).")
        val total = sacks + inventory

        if (total == 0L) {
            Msg.chat("§7You have 0 Worm Membranes right now — nothing to forge.")
            return
        }
        if (chamberAhAvg < 0 || mixtureCost < 0) {
            Msg.chat("§7Still fetching chamber/mixture prices — try again in a few seconds.")
            return
        }

        val chambers = (total / MEMBRANE_PER_CHAMBER).toInt()
        val leftover = total % MEMBRANE_PER_CHAMBER
        if (chambers == 0) {
            Msg.chat("§7Only §f$total§7/$MEMBRANE_PER_CHAMBER Worm Membranes (§f$sacks §7sacks + §f$inventory §7inventory) — not enough for one chamber yet.")
            return
        }

        val perChamberMs = (CHAMBER_BASE_FORGE_MS * QUICK_FORGE_20_MULTIPLIER).toLong()
        // 7 forge slots run in parallel, so total time = number of rounds * per-chamber time.
        val rounds = (chambers + FORGE_SLOTS - 1) / FORGE_SLOTS
        val totalTimeMs = perChamberMs * rounds

        // Every figure is "7d avg (current)": the main number is on the 7-day averages, the bracketed
        // one on today's prices (chamber = current cheapest BIN, mixture = live bazaar insta-buy).
        // Falls back to the other side if one is unavailable so a failed fetch doesn't blank the line.
        val chamber7 = chamberAhAvg
        val chamberNow = chamberLowestBin
        val mixture7 = if (mixtureWeekAvg > 0) mixtureWeekAvg else mixtureCost
        val mixtureNow = mixtureCost

        val cost7 = (mixture7 + FORGE_COST) * chambers
        val costNow = if (mixtureNow > 0) (mixtureNow + FORGE_COST) * chambers else -1.0
        val gain7 = chamber7 * chambers
        val gainNow = if (chamberNow > 0) chamberNow * chambers else -1.0
        val net7 = gain7 - cost7
        val netNow = if (costNow >= 0 && gainNow >= 0) gainNow - costNow else Double.NaN

        fun pair(main: String, current: Double, fmt: (Double) -> String): String =
            "$main §7(${if (current.isNaN() || current < 0) "§8?§7" else fmt(current)})"
        fun netColor(v: Double) = if (v >= 0) "§a" else "§c"

        Msg.chat("§6§lWorm Calc §r§7— §f$total §7membranes (§f$sacks §7sacks + §f$inventory §7inv) → §f$chambers chambers §7($leftover left)")
        Msg.chat("§7Format: §f7d avg §7(lbin)")
        Msg.chat("§8• §7Chamber: " + pair("§f${short(chamber7)}", chamberNow) { "§f${short(it)}" })
        Msg.chat("§8• §7Mixture: " + pair("§f${short(mixture7)}", mixtureNow) { "§f${short(it)}" })
        Msg.chat("§8• §7Forge time: §f${fmtDur(totalTimeMs)} §7($FORGE_SLOTS slots, $rounds round${if (rounds == 1) "" else "s"})")
        Msg.chat("§8• §7Total cost: " + pair("§c-${short(cost7)}", costNow) { "§c-${short(it)}" } + " §7(buy §f$chambers §7mixtures)")
        Msg.chat("§8• §7Total gain: " + pair("§a+${short(gain7)}", gainNow) { "§a+${short(it)}" })
        Msg.chat("§8• §7Profit: " + pair("${netColor(net7)}${signed(net7)}", netNow) { "${netColor(it)}${signed(it)}" })
    }

    // ================================================================================
    //  Forge tracker: one end time (epoch ms) per tracked chamber, so chambers started at different
    //  times each finish on their own clock. Filled with exact times by [WormForge] (read straight off
    //  the forge menu's "Time Remaining") whenever auto mode visits the forge.
    //  Persisted on [WormFishing] so it survives restarts; shown in the Macro HUD.
    // ================================================================================

    // Highest end time already announced, so each finishing batch notifies exactly once.
    private var forgeNotifiedUpTo = 0L

    val forgePerChamberMs get() = (CHAMBER_BASE_FORGE_MS * QUICK_FORGE_20_MULTIPLIER).toLong()

    private fun forgeEnds(): List<Long> =
        WormFishing.forgeTimers.split(',').mapNotNull { it.trim().toLongOrNull() }.sorted()

    private fun setForgeEnds(ends: List<Long>) {
        WormFishing.forgeTimers = ends.sorted().joinToString(",")
        ModuleManager.saveConfigurations()
    }


    /** Worm Membranes in the Fishing Sack right now (start check + live [Sacks] deltas), -1 if unknown. */
    val sackMembranes: Long get() = lastMembraneSack

    /** Tracked chambers that have finished and can be claimed. */
    fun forgeReadyCount(): Int {
        val now = System.currentTimeMillis()
        return forgeEnds().count { it <= now }
    }

    /** Forge slots not holding a tracked chamber (assumes all [FORGE_SLOTS] are unlocked). */
    fun forgeFreeSlots(): Int = (FORGE_SLOTS - forgeEnds().size).coerceAtLeast(0)

    /** Exact end times read off the forge menu by [WormForge]; replaces whatever was tracked. */
    fun forgeSetExact(ends: List<Long>) {
        setForgeEnds(ends)
        forgeNotifiedUpTo = System.currentTimeMillis()
        if (ends.isEmpty()) return
        val now = System.currentTimeMillis()
        val n = ends.size
        Msg.chat("§6[Forge] §7Tracking §f$n §7chamber${if (n == 1) "" else "s"} — next done in §f${fmtDur((ends.min() - now).coerceAtLeast(0))}§7, all done in §f${fmtDur((ends.max() - now).coerceAtLeast(0))}§7.")
    }

    /**
     * For the Macro HUD: (next chamber, all done) as short text. "None" when nothing is tracked,
     * "Ready" (with a count) once chambers can be claimed. [fmt] formats a duration in ms.
     */
    fun forgeHudText(fmt: (Long) -> String): Pair<String, String> {
        val ends = forgeEnds()
        if (ends.isEmpty()) return "None" to "None"
        val now = System.currentTimeMillis()
        val ready = ends.count { it <= now }
        val next = if (ready > 0) "Ready ($ready)" else fmt(ends.first() - now)
        val all = if (ends.last() <= now) "Ready" else fmt(ends.last() - now)
        return next to all
    }

    /** Worm Membranes read straight off the Fishing Sack menu (Worm Fishing's start check): the live count. */
    fun setSackFromMenu(n: Long) {
        lastMembraneSack = n.coerceAtLeast(0L)
        Msg.chat("§b[Wormfish] §7Fishing Sack: §f$n §7Worm Membranes")
    }

    private fun forgeTick() {
        val ends = forgeEnds()
        if (ends.isEmpty()) return
        val now = System.currentTimeMillis()
        val newlyReady = ends.count { it in (forgeNotifiedUpTo + 1)..now }
        if (newlyReady == 0) return
        forgeNotifiedUpTo = now
        val ready = ends.count { it <= now }
        val word = if (ready == 1) "chamber" else "chambers"
        Msg.chat("§6[Forge] §a$ready Gemstone $word ready" +
            if (WormFishing.autoMode) "§7, auto mode claims at the next reel." else " §7(turn on Auto Mode in Worm Fishing to claim them).")
    }

    /** `/f5 wormhelper calc lifetime` -- all-time membrane stats from the persisted lifetime counters, plus
     * what that many membranes is worth forged. Uses 7d-avg prices like [wormCalc]. */
    fun wormCalcLifetime() {
        val caught = lifetimeMembranes.toLong()
        if (caught == 0L) {
            Msg.chat("§7No lifetime membranes recorded yet — enable the Worm Helper module and go fishing.")
            return
        }
        val chambers = caught / MEMBRANE_PER_CHAMBER
        val perChamberMs = (CHAMBER_BASE_FORGE_MS * QUICK_FORGE_20_MULTIPLIER).toLong()
        val rounds = (chambers + FORGE_SLOTS - 1) / FORGE_SLOTS

        Msg.chat("§6§lWorm Calc — Lifetime")
        Msg.chat("§8• §7Membranes caught: §f$caught §7(§f$chambers §7chambers, ${caught % MEMBRANE_PER_CHAMBER} left)")
        Msg.chat("§8• §7Rings caught: §f$lifetimeRings §7(${priceOrDash(lifetimeRings * ringNpcPrice)} NPC value)")
        if (lastMembraneSack >= 0) Msg.chat("§8• §7Currently in sack: §f$lastMembraneSack")
        Msg.chat("§8• §7Tracked earnings: §a${short(lifetimeCoins)}")
        if (chamberAhAvg < 0 || mixtureCost < 0) {
            Msg.chat("§7Still fetching chamber/mixture prices — rerun in a few seconds for the forge breakdown.")
            refreshPrices()
            return
        }
        val mixture7 = if (mixtureWeekAvg > 0) mixtureWeekAvg else mixtureCost
        val cost = (mixture7 + FORGE_COST) * chambers
        val gain = chamberAhAvg * chambers
        val net = gain - cost
        Msg.chat("§8• §7If all forged: §f${fmtDur(perChamberMs * rounds)} §7($FORGE_SLOTS slots), cost §c-${short(cost)} §7(§f$chambers §7mixtures), gain §a+${short(gain)}, " +
            "profit ${if (net >= 0) "§a" else "§c"}${signed(net)}")
    }

    // ================================================================================
    //  Formatting
    // ================================================================================

    private fun short(v: Double): String {
        val a = kotlin.math.abs(v)
        val sign = if (v < 0) "-" else ""
        return when {
            a >= 1_000_000_000 -> "$sign%.2fB".format(a / 1e9)
            a >= 1_000_000 -> "$sign%.2fM".format(a / 1e6)
            a >= 1_000 -> "$sign%.1fk".format(a / 1e3)
            else -> "$sign%.0f".format(a)
        }
    }

    private fun signed(v: Double): String = (if (v >= 0) "+" else "") + short(v)

    private fun fmtDur(ms: Long): String {
        var s = ms / 1000
        val h = s / 3600; s %= 3600
        val m = s / 60; s %= 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "%dm %02ds".format(m, s)
            else -> "${s}s"
        }
    }
}
