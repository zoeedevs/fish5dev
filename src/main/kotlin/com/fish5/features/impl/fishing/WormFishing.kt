package com.fish5.features.impl.fishing

import com.fish5.clickgui.settings.Setting.Companion.inGroup
import com.fish5.clickgui.settings.Setting.Companion.withDependency
import com.fish5.clickgui.settings.impl.BooleanSetting
import com.fish5.clickgui.settings.impl.GroupSetting
import com.fish5.clickgui.settings.impl.NumberSetting
import com.fish5.clickgui.settings.impl.RangeSetting
import com.fish5.clickgui.settings.impl.SelectorSetting
import com.fish5.clickgui.settings.impl.StringSetting
import com.fish5.automation.Msg
import com.fish5.config.ConfigHelpers
import com.fish5.failsafe.FailsafeManager
import com.fish5.features.Macro
import com.fish5.features.MacroPause
import com.fish5.macro.MacroState
import com.fish5.macro.MacroStateManager
import com.fish5.rotation.RotationManager
import com.fish5.session.RestScheduler
import com.fish5.utils.ClientUtils
import net.minecraft.client.player.LocalPlayer
import net.minecraft.util.Mth
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.projectile.FishingHook
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import java.util.concurrent.ThreadLocalRandom

/**
 * Worm Fishing macro (Macros tab). Uses the shared [Fishing] settings (pond, aim, sea creatures) and
 * adds combat + party:
 *  - cast/reel loop ([tick]); our sea creatures counted by [SeaCreatureTracker] (/f5 sc).
 *  - Solo: kill at the SC threshold or the despawn timer, using the weapon until ours are dead.
 *  - Lootshare: hit once at our threshold and once when the killer reaches it; optional override kill.
 *  - Killer: at our threshold, wait (party at cap + 2 s / fixed delay / chat message), then kill everyone's.
 * Party members come from /p list ([Party]); nothing assumes the others run fish5.
 */
object WormFishing : Macro(
    name = "Worm Fishing",
    area = "Crystal Hollows",
    description = "Fishes worms in the Crystal Hollows and kills them at your sea creature limit.",
    status = Status.TESTING,
    instructions = "1. Go to a lava or water fishing spot in the Crystal Hollows.\n" +
        "2. Put your fishing rod and weapon in your hotbar, and set the weapon slot in Combat.\n" +
        "3. Set up pond selection, aim and sea creatures on the Fishing page.\n" +
        "4. Party fishing: be in the Hypixel party first, then pick Lootshare or Killer under Party.\n" +
        "5. Press START (or your General Macro Toggle key).",
) {

    val checkSacks by BooleanSetting(
        "Check sacks on start", true,
        desc = "Before fishing, open /sacks, right-click your Fishing Sack and read how many Worm Membranes are " +
            "stored, so the forge alert and profit tracker start from the real count.",
    )
    val checkForge by BooleanSetting(
        "Check forge on start", true,
        desc = "Before fishing, /call forge and read how long each Gemstone Chamber has left, so the forge timers " +
            "and auto mode start from the real state. Only reads, never claims. Needs the Forge contact on your Abiphone.",
    )

    // -- Auto mode (forging / claiming / listing, run by WormAuto between reels) ---------------------

    private val autoCard = registerSetting(GroupSetting("Auto Mode", "Fully automatic forging, claiming and listing of Gemstone Chambers."))
    val autoMode by BooleanSetting(
        "Auto mode", false,
        desc = "Once you have 100 Worm Membranes for a free forge slot, buy the Gemstone Mixture on the bazaar and " +
            "forge a chamber; claim chambers when they finish; optionally list them on the AH.",
    ).inGroup(autoCard)
    private val forgeModeSetting = registerSetting(SelectorSetting(
        "How to forge", "Call", listOf("Manual", "Call"),
        desc = "Call: /call forge between reels, you keep fishing your spot. Manual: warp to the Forge and etherwarp " +
            "to the Forger. That leaves the Crystal Hollows, so afterwards it finds a new lava spot and waits for you.",
    ).withDependency { autoMode }.inGroup(autoCard))
    val forgeCallMode get() = forgeModeSetting.value == 1
    val forgeClickDelay = registerSetting(RangeSetting(
        "Forge click delay", 105, 195, 25, 1000, 5, unit = " ms",
        desc = "Delay between forge menu clicks, picked at random in this range. Lower is faster.",
    ).withDependency { autoMode }.inGroup(autoCard))

    private val listCard = registerSetting(GroupSetting("List Chambers", "Put Gemstone Chambers on the Auction House."))
    val listChambers by BooleanSetting(
        "List chambers", false,
        desc = "Whenever there's a Gemstone Chamber in your inventory, pause between reels and BIN-list it from " +
            "the fishing spot; also collects sold ones. Works with or without Auto mode. Needs a Booster Cookie for /ah.",
    ).inGroup(listCard)
    private val priceModeSetting = registerSetting(SelectorSetting(
        "Price", "Lowest BIN", listOf("Lowest BIN", "7d median", "Set price"),
        desc = "Lowest BIN: 100k under the cheapest chamber on the AH. 7d median: the median of the last week's " +
            "hourly AH prices. Set price: the price below.",
    ).withDependency { listChambers }.inGroup(listCard))
    val priceLowestBin get() = priceModeSetting.value == 0
    val priceMedian get() = priceModeSetting.value == 1
    val setPriceM by NumberSetting<Int>(
        "Set price", 10, 1, 50, 1, unit = "M", desc = "Set price: list every chamber at this price.",
    ).withDependency { listChambers && priceModeSetting.value == 2 }.inGroup(listCard)
    // millions, in 100k steps; was an Int in whole millions, which still reads fine as a Double
    val minPriceM by NumberSetting<Double>(
        "Minimum price", 5.0, 0.1, 50.0, 0.1, unit = "M",
        desc = "Lowest BIN: never list below this, however cheap the AH gets. Drag in 100k steps, or click the " +
            "value to type it (5.3, 5.3m, 5300k or 5300000).",
        parseInput = ::parsePriceM,
    ).withDependency { listChambers && priceLowestBin }.inGroup(listCard)
    val relist by BooleanSetting(
        "Relist", false,
        desc = "Lowest BIN: cancel chambers that haven't sold after the time below and list them again at the new lowest BIN.",
    ).withDependency { listChambers && priceLowestBin }.inGroup(listCard)
    val relistMinutes by NumberSetting<Int>(
        "Relist after", 120, 15, 360, 15, unit = " min", desc = "How long a listing waits before it's relisted.",
    ).withDependency { listChambers && priceLowestBin && relist }.inGroup(listCard)

    /** Typed price -> millions: "5.3" / "5.3m" = 5.3M, "5300k" = 5.3M, a bare number over 1000 = coins. */
    private fun parsePriceM(text: String): Double? {
        val t = text.lowercase().replace(",", "")
        return when {
            t.endsWith("m") -> t.dropLast(1).toDoubleOrNull()
            t.endsWith("k") -> t.dropLast(1).toDoubleOrNull()?.div(1000.0)
            else -> t.toDoubleOrNull()?.let { if (it > 1000.0) it / 1_000_000.0 else it }
        }
    }

    // persisted state: comma-separated epoch-ms forge end times / AH listing times
    var forgeTimers by StringSetting("Forge timers", "", 256, desc = "Tracked forge chamber end times.").hide()
    var ahListings by StringSetting("AH listings", "", 512, desc = "When each listed chamber went up.").hide()

    // -- Combat ------------------------------------------------------------------------------------

    private val combatCard = registerSetting(GroupSetting("Combat", "How sea creatures are killed."))
    private val weaponTypeSetting = registerSetting(SelectorSetting(
        "Weapon type", "Wither Impact", listOf("Ability", "Wither Impact"),
        desc = "Ability: right-click ability weapon. Wither Impact: Hyperion-style blade. It teleports you " +
            "forward, so it always looks down (or at the target), never at the fishing spot.",
    ).inGroup(combatCard))
    val weaponSlot by NumberSetting<Double>("Weapon slot", 2.0, 1.0, 9.0, 1.0, desc = "Hotbar slot of the weapon.").inGroup(combatCard)
    private val combatRotationSetting = registerSetting(SelectorSetting(
        "Combat rotation", "Down", listOf("None", "Down", "Target"),
        desc = "None: keep the fishing rotation and use (Wither Impact uses Down instead). Down: look down and use. " +
            "Target: turn to a random point within 1 block of the nearest worm and use once your aim passes within 1 block of it.",
    ).inGroup(combatCard))
    private val attackDelay = registerSetting(RangeSetting(
        "Attack delay", 100, 150, 0, 1000, 5, unit = " ms",
        desc = "Solo: wait a random time in this range after the threshold or timer is hit before attacking.",
    ).inGroup(combatCard))
    val ignoreRunic by BooleanSetting(
        "Ignore runic", false,
        desc = "Runic sea creatures still count toward your threshold, but combat stops once only runic ones are " +
            "left. They have far more health and die over time from the splash of later kills. If Hypixel says your " +
            "cap is full and only runic ones are left, they're killed anyway so fishing doesn't stall.",
    ).inGroup(combatCard)

    // -- Party -------------------------------------------------------------------------------------

    private val partyCard = registerSetting(GroupSetting("Party", "Fishing with others. Members come from /p list."))
    private val partyModeSetting = registerSetting(SelectorSetting(
        "Party mode", "None", listOf("None", "Lootshare", "Killer"),
        desc = "None: normal. Lootshare: at your limit, use your ability but don't kill. " +
            "Killer: at your limit, wait for your lootsharers, then kill everyone's.",
    ).inGroup(partyCard))
    private val inParty get() = partyModeSetting.value != 0
    private val isLootshare get() = partyModeSetting.value == 1
    private val isKiller get() = partyModeSetting.value == 2
    val killerName by StringSetting(
        "Killer", "", 16,
        desc = "Lootshare: the player who kills. Once they have your SC threshold alive, you hit once and keep " +
            "fishing, even if you aren't at yours. Empty: any party member.",
    ).withDependency { isLootshare }.inGroup(partyCard)
    private val waitForSetting = registerSetting(SelectorSetting(
        "Wait for", "Party cap", listOf("Party cap", "Fixed delay", "Chat message", "Weapon swap"),
        desc = "Killer, once you're at your limit. Party cap: every party member is at the threshold, then 2 s " +
            "for their hits. Fixed delay: kill after the delay no matter what. Chat message: a party member says the kill message. " +
            "Weapon swap: every party member fishing near you swaps to their weapon and back to their rod (they've hit), then kill.",
    ).withDependency { isKiller }.inGroup(partyCard))
    val lootshareWeapon by StringSetting(
        "Lootshare weapon", "Dreadlord Sword", 32,
        desc = "Weapon swap: the item your party members hit with (name or SkyBlock id).",
    ).withDependency { isKiller && waitForSetting.value == 3 }.inGroup(partyCard)
    val killDelay by NumberSetting<Double>(
        "Kill delay", 3.0, 1.0, 10.0, 1.0, unit = " s",
        desc = "Fixed delay: seconds after you reach your limit before killing.",
    ).withDependency { isKiller && waitForSetting.value == 1 }.inGroup(partyCard)
    val killMessage by StringSetting(
        "Kill message", "Ready", 32,
        desc = "Chat message: kill when a party member's party chat message contains this.",
    ).withDependency { isKiller && waitForSetting.value == 2 }.inGroup(partyCard)
    val override by BooleanSetting(
        "Override", false,
        desc = "If someone takes too long, go ahead and kill anyway.",
    ).withDependency { inParty }.inGroup(partyCard)
    val overrideAfter by NumberSetting<Double>(
        "Override after", 30.0, 5.0, 180.0, 5.0, unit = " s",
        desc = "How long to wait for the others before overriding.",
    ).withDependency { inParty && override }.inGroup(partyCard)
    val overrideWeaponSlot by NumberSetting<Double>(
        "Override weapon slot", 3.0, 1.0, 9.0, 1.0,
        desc = "Lootshare: hotbar slot of the weapon that actually kills, used when overriding.",
    ).withDependency { isLootshare && override }.inGroup(partyCard)
    val chatMode by BooleanSetting(
        "Chat mode", true,
        desc = "Say a message in party chat when you hit your sea creature limit.",
    ).withDependency { inParty }.inGroup(partyCard)
    val readyMessage by StringSetting("Ready message", "Ready", 32, desc = "What to send in party chat.")
        .withDependency { inParty && chatMode }.inGroup(partyCard)

    enum class WeaponType { ABILITY, WITHER_IMPACT }
    enum class CombatRotation { NONE, DOWN, TARGET }
    enum class PartyMode { NONE, LOOTSHARE, KILLER }
    enum class WaitFor { PARTY_CAP, FIXED_DELAY, CHAT_MESSAGE, WEAPON_SWAP }

    val weaponType get() = WeaponType.entries[weaponTypeSetting.value]
    val combatRotation get() = CombatRotation.entries[combatRotationSetting.value]
    val partyMode get() = PartyMode.entries[partyModeSetting.value]
    val waitFor get() = WaitFor.entries[waitForSetting.value]

    /** Wither Impact teleports forward, so it never fires along the fishing rotation. */
    private val attackRotation
        get() = if (weaponType == WeaponType.WITHER_IMPACT && combatRotation == CombatRotation.NONE) CombatRotation.DOWN else combatRotation

    // -- Loop ------------------------------------------------------------------------------------
    //
    // EQUIP (rod in hand) -> AIM (CastAim plan + eased rotation) -> ROTATE (wait for it) -> CAST (use
    // click) -> WAIT_HOOK (bobber spawns) -> FISHING (wait for the "!!!" bite stand over our bobber)
    // -> REEL (use click, wait for the bobber to go) -> EQUIP again after a randomized recast delay.
    // An attack (one HIT, or a KILL until the creatures are dead) cuts in at the next safe moment:
    // reel in, ATK_SWAP -> ATK_AIM -> ATK_ROTATE -> ATK_USE -> back to EQUIP.
    // Everything is read from the world, nothing assumes fixed fishing-speed timing.

    private enum class Phase { EQUIP, AIM, ROTATE, CAST, WAIT_HOOK, FISHING, REEL, ATK_SWAP, ATK_AIM, ATK_ROTATE, ATK_USE }
    private enum class Attack { HIT, KILL }

    private var phase = Phase.EQUIP
    private var phaseAt = 0L      // when the current phase started
    private var actAt = 0L        // don't act in this phase before this time (randomized delays)
    private var planYaw = 0f
    private var planPitch = 0f
    private var biteTicks = 0
    private var biteLatched = false
    private var failedPlans = 0
    private var failedCasts = 0
    private var reelClicks = 0

    // attack in progress / requested
    private var attack: Attack? = null
    private var attackSlot = 0            // 0-8
    private var attackIncludesOthers = false
    private var attackStartedAt = 0L
    private var killGroupCutoff = Long.MAX_VALUE  // a KILL only targets creatures spawned by this time
    private var attackSkipsRunic = false  // ignoreRunic for this attack (off when Hypixel says we're full)
    private var attackUses = 0            // use-clicks so far in this attack
    private var lastUseAt = 0L
    private var postHitWaitMs = 400L      // after the hit message, let the deaths come in before re-checking
    private var killableBefore = 0        // killable count at the previous check of this kill
    private var stalledUses = 0           // uses in a row that killed nothing
    private var groupDied = false         // something in this kill's group has died
    private var judgedUses = 0            // attackUses when the last use's result was counted
    private var targetId = -1
    private var killCooldownUntil = 0L    // after giving up on a kill, don't retrigger straight away

    // solo
    private var soloKillDueAt = 0L
    private var lastFullKillAt = 0L       // last kill started only because Hypixel said we're full
    // lootshare
    private var ownHitLatched = false     // one hit per time we reach our threshold
    private var killerHitLatched = false  // one hit per time the killer reaches it
    private var lootWaitStart = 0L        // when we reached our limit (for the override)
    // killer
    private var killerWaitStart = 0L      // when we reached our limit and started waiting
    private var partyCapSince = 0L
    private var partyCapDelayMs = 0
    private var swapReactMs = 0
    private var lastSwapReport = 0L
    private var killMessageAt = 0L
    private var warnedNoMembers = false
    private var readySent = false

    private const val HOOK_SPAWN_TIMEOUT_MS = 1500L
    private const val NOT_IN_FLUID_TIMEOUT_MS = 4000L   // landed on a block / stuck: reel and recast
    private const val NO_BITE_TIMEOUT_MS = 60_000L      // something is off; reel and recast
    private const val REEL_TIMEOUT_MS = 1500L
    private const val AIM_TOLERANCE_DEG = 1.5f
    private const val CAST_WHILE_TURNING_DEG = 15f      // same-block nudge up to this: cast mid-turn (more = we were looking elsewhere)
    private const val TARGET_RADIUS = 1.0               // aim line must pass within this many blocks of the target to use
    private const val TARGET_AIM_RADIUS = 0.8           // aim point picked this far from the centre at most (margin for rotation noise)
    private const val RECAST_MIN_MS = 8
    private const val RECAST_MAX_MS = 84
    private const val RECAST_SHAPE_B = 4.085            // with a = 2: median (38 - 8) / (84 - 8) of the range
    private const val USE_RESULT_TIMEOUT_MS = 1200L     // no hit message by then (weapon that prints none): judge anyway
    private const val RUNIC_STALL_USES = 2              // hits survived after the rest of the group died = runic
    private const val KILL_TIMEOUT_MS = 25_000L
    private const val KILL_RETRY_COOLDOWN_MS = 30_000L
    private const val CHAT_MESSAGE_MAX_AGE_MS = 60_000L // a kill message this long before our limit still counts
    private const val SWAP_LOOKBACK_MS = 3000L          // a weapon swap that began this long before our limit still counts
    private const val SWAP_REPORT_MS = 15_000L          // how often to say who we're still waiting on
    private const val SERVER_FULL_FRESH_MS = 10_000L    // Hypixel's "not enough space" line counts as "at cap" this long
    private const val FULL_KILL_REPEAT_MS = 15_000L     // still full this soon after a forced kill: it didn't help, back off

    // allocated once; called for the few armor stands right over our bobber
    private val BITE_STAND = java.util.function.Predicate<ArmorStand> { stand ->
        val n = stand.customName
        n != null && n.string.contains("!!!")
    }

    // -- Macro HUD ---------------------------------------------------------------------------------

    override val hudStatus: String
        get() = when {
            attack == Attack.KILL && phase.ordinal >= Phase.ATK_SWAP.ordinal -> "Killing sea creatures"
            attack == Attack.HIT && phase.ordinal >= Phase.ATK_SWAP.ordinal -> "Hitting (lootshare)"
            killerWaitStart != 0L -> "Waiting for party"
            lootWaitStart != 0L && phase == Phase.FISHING -> "Waiting for killer"
            else -> when (phase) {
                Phase.EQUIP, Phase.AIM, Phase.ROTATE, Phase.CAST, Phase.WAIT_HOOK -> "Casting"
                Phase.FISHING -> "Waiting for bite"
                Phase.REEL -> "Reeling in"
                else -> "Attacking"
            }
        }

    private val safePoint = java.util.function.BooleanSupplier {
        attack == null && (phase == Phase.EQUIP || phase == Phase.AIM || phase == Phase.ROTATE || phase == Phase.CAST)
    }

    // -- scheduled breaks: carry on afterwards if we're still at the spot ---------------------------

    private const val RESUME_MAX_DIST_SQ = 6.0 * 6.0
    private var stoppedForBreak = false
    private var spotX = 0.0
    private var spotY = 0.0
    private var spotZ = 0.0

    // An in-game break leaves us where we were, so just start again. After a disconnect break the
    // Crystal Hollows instance is newly generated and the spot no longer exists, so tell the user instead.
    private val resumeListener = Runnable {
        if (!stoppedForBreak) return@Runnable
        stoppedForBreak = false
        val p = mc.player
        val atSpot = p != null && p.distanceToSqr(spotX, spotY, spotZ) <= RESUME_MAX_DIST_SQ &&
            com.fish5.features.impl.skyblock.WormFinder.inCrystalHollows()
        if (!atSpot) {
            Msg.chat("§eBreak over. §7You're no longer at your fishing spot (after a disconnect the Crystal Hollows " +
                "instance is new), so $name didn't restart. Go to a spot and press START.")
            return@Runnable
        }
        Msg.chat("§aBreak over, carrying on with $name.")
        ClientUtils.scheduleClientTask(mc, delay(1500, 4000).toLong()) { if (!running) start() }
    }

    init {
        RestScheduler.addResumeListener(resumeListener)
    }

    private val partyChat: (String, String) -> Unit = { sender, message ->
        if (running && isKiller && waitFor == WaitFor.CHAT_MESSAGE) {
            val want = killMessage.trim()
            if (want.isNotEmpty() && message.contains(want, true) && (Party.members.isEmpty() || Party.isMember(sender))) {
                killMessageAt = System.currentTimeMillis()
                Msg.dbg("Killer: kill message from $sender")
            }
        }
    }

    override fun onStart() {
        failedPlans = 0; failedCasts = 0
        attack = null; killCooldownUntil = 0L; soloKillDueAt = 0L; lastFullKillAt = 0L
        ownHitLatched = false; killerHitLatched = false; lootWaitStart = 0L
        resetKillerWait()
        killMessageAt = 0L; warnedNoMembers = false; readySent = false
        PartyWeaponWatch.clear()
        MacroPause.parked = false
        stoppedForBreak = false
        mc.player?.let { spotX = it.x; spotY = it.y; spotZ = it.z }
        if (inParty) {
            Party.wantAutoRefresh(Party.WANT_MACRO, true)
            Party.refresh()
        }
        Party.partyChatListener = partyChat
        // a line already out (cast by hand) is simply fished
        go(if (mc.player?.fishing != null) Phase.FISHING else Phase.EQUIP)
        RestScheduler.setSafePoint(safePoint)
        if (checkSacks || checkForge) startSackCheck(checkSacks, checkForge)
    }

    // -- sack check (start only, not on resume) ------------------------------------------------------
    //
    // /sacks -> right-click the Fishing Sack -> read "Stored: 121/60.5k" off Worm Membrane -> close.
    // Runs on AutoWorker (blocking menu waits); the loop holds meanwhile because the state is RECOVERING,
    // and the GUI failsafe is told the menus are ours.

    private var sackCheckRunning = false
    private val storedPattern = Regex("""Stored:\s*([\d,]+)""")

    // sacks and/or forge, one worker job, one RECOVERING window
    private fun startSackCheck(sacks: Boolean, forge: Boolean) {
        if (mc.player?.fishing != null) return  // started with a line already out: don't yank it for a menu
        sackCheckRunning = true
        MacroStateManager.setState(MacroState.State.RECOVERING)
        FailsafeManager.setExpectingGui(true)
        val submitted = com.fish5.automation.AutoWorker.submit("Worm Fishing start check") {
            if (sacks) {
                val n = runCatching { readMembranes() }.getOrNull()
                mc.execute { reportSack(n) }
            }
            if (forge && !com.fish5.automation.AutoWorker.aborted()) {
                if (sacks) com.fish5.automation.AutoWorker.sleepRandom(700L, 300L)  // a breather between menus
                val ok = runCatching { com.fish5.features.impl.skyblock.WormForge.readTimersBlocking() }.getOrDefault(false)
                if (!ok && !com.fish5.automation.AutoWorker.aborted()) {
                    Msg.chat("§eCouldn't read the forge with §f/call forge§e, carrying on without it " +
                        "§8(no Forge contact? turn off Check forge on start)§e.")
                }
            }
            mc.execute { finishSackCheck() }
        }
        if (!submitted) { reportSack(null); finishSackCheck() }
    }

    private fun reportSack(n: Long?) {
        if (n != null) com.fish5.features.impl.skyblock.WormfishProfitTracker.setSackFromMenu(n)
        else Msg.chat("§eCouldn't read your Fishing Sack, carrying on without it.")
    }

    private fun finishSackCheck() {
        if (!sackCheckRunning) return
        sackCheckRunning = false
        FailsafeManager.setExpectingGui(false)
        if (running && MacroStateManager.getCurrentState() == MacroState.State.RECOVERING) {
            MacroStateManager.setState(MacroState.State.RUNNING)
            go(Phase.EQUIP, delay(250, 500))
        }
    }

    // worker thread
    private fun readMembranes(): Long? {
        com.fish5.automation.Menus.closeScreen(mc)
        com.fish5.automation.Menus.waitForScreenClosed(mc, 2000)
        com.fish5.automation.ClientActions.sendCommand(mc, "/sacks")
        try {
            if (!com.fish5.automation.Menus.waitForTitle(mc, "sack", 5000)) return null
            // exact names: "Bronze Trophy Fishing Sack" etc. also contain "fishing sack"
            val sack = findFishingSack()
            if (sack < 0) { Msg.LOG.warn("[SackCheck] no Large/Medium/Small Fishing Sack in: {}", com.fish5.automation.Menus.dumpContainer(mc)); return null }
            com.fish5.automation.AutoWorker.sleep(ClientUtils.getGuiClickDelayMs(true))
            com.fish5.automation.Menus.clickSlot(mc, sack, 1, net.minecraft.world.inventory.ContainerInput.PICKUP)  // right-click opens it
            // the opened menu must be exactly "Fishing Sack", not some other sack
            if (!waitForExactTitle("fishing sack", 4000)) {
                Msg.LOG.warn("[SackCheck] opened '{}' instead of the Fishing Sack", com.fish5.automation.Menus.currentTitle(mc))
                return null
            }
            val slot = com.fish5.automation.Menus.waitForSlotNamed(mc, "worm membrane", 2500)
            if (slot < 0) return 0L  // the Fishing Sack opened but holds no membranes
            for (line in com.fish5.automation.Menus.loreLines(com.fish5.automation.Menus.stackAt(mc, slot))) {
                val m = storedPattern.find(line) ?: continue
                return m.groupValues[1].replace(",", "").toLongOrNull()
            }
            return null
        } finally {
            com.fish5.automation.AutoWorker.sleep(ClientUtils.getGuiClickDelayMs(false))
            com.fish5.automation.Menus.closeScreen(mc)
            com.fish5.automation.Menus.waitForScreenClosed(mc, 2000)
        }
    }

    // the biggest fishing sack the player has; any of them opens the shared Fishing Sack
    private fun findFishingSack(): Int {
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline && !com.fish5.automation.AutoWorker.aborted()) {
            for (name in FISHING_SACKS) {
                val i = com.fish5.automation.Menus.findSlotByName(mc, name, true)
                if (i >= 0) return i
            }
            com.fish5.automation.AutoWorker.sleep(100)
        }
        return -1
    }

    private val FISHING_SACKS = arrayOf("Large Fishing Sack", "Medium Fishing Sack", "Small Fishing Sack")

    private fun waitForExactTitle(title: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !com.fish5.automation.AutoWorker.aborted()) {
            if (com.fish5.automation.Menus.currentTitle(mc)?.equals(title, true) == true) return true
            com.fish5.automation.AutoWorker.sleep(100)
        }
        return false
    }

    override fun onStop() {
        abortSackCheck()
        RestScheduler.setSafePoint(null)
        // the scheduler stops us right before it starts the break, so the break is still "due" here
        stoppedForBreak = RestScheduler.isBreakDue() || RestScheduler.isOnBreak()
        SeaCreatureTracker.want(SeaCreatureTracker.WANT_MACRO, false)
        Party.wantAutoRefresh(Party.WANT_MACRO, false)
        if (Party.partyChatListener === partyChat) Party.partyChatListener = null
        phase = Phase.EQUIP
        attack = null
    }

    private fun abortSackCheck() {
        if (!sackCheckRunning) return
        sackCheckRunning = false
        com.fish5.automation.AutoWorker.requestAbort()
        FailsafeManager.setExpectingGui(false)
    }

    override fun onSuspend() {
        abortSackCheck()
        RestScheduler.setSafePoint(null)
        MacroPause.parked = false
    }

    // carry on from the kept state: fish the line if it's still out, otherwise re-equip (a pending
    // attack takes over from EQUIP)
    override fun onResume() {
        attackStartedAt = 0L
        go(if (attack == null && mc.player?.fishing != null) Phase.FISHING else Phase.EQUIP)
        RestScheduler.setSafePoint(safePoint)
    }

    private fun go(next: Phase, delayMs: Int = 0) {
        phase = next
        phaseAt = System.currentTimeMillis()
        actAt = phaseAt + delayMs
        if (next == Phase.FISHING) { biteTicks = 0; biteLatched = false }
        if (next == Phase.REEL) reelClicks = 0
    }

    private fun fail(why: String) {
        Msg.chat("§c$name stopped: §7$why")
        stop()
    }

    private fun delay(min: Int, max: Int) = ConfigHelpers.getRandomizedDelay(min, max)
    private fun atkStep() = Fishing.attackStepMs()
    private fun useStep() = Fishing.killClickMs()

    /**
     * Gap between the line coming back and the next cast: [RECAST_MIN_MS]-[RECAST_MAX_MS], skewed to the
     * low half like a reaction time. Kumaraswamy(a = 2, b = 4.085) over the range: rarely right at the
     * minimum, most often around 36 ms, median ~38 ms, with a thin tail out to the maximum.
     */
    private fun recastDelay(): Int {
        val u = ThreadLocalRandom.current().nextDouble()
        val x = Math.sqrt(1.0 - Math.pow(1.0 - u, 1.0 / RECAST_SHAPE_B))  // inverse CDF, a = 2
        return RECAST_MIN_MS + Math.round(x * (RECAST_MAX_MS - RECAST_MIN_MS)).toInt()
    }

    private fun requestAttack(kind: Attack, slot1to9: Double, includeOthers: Boolean, skipRunic: Boolean = ignoreRunic) {
        if (attack != null) return
        attack = kind
        attackSlot = slot1to9.toInt() - 1
        attackIncludesOthers = includeOthers
        attackSkipsRunic = skipRunic
        attackUses = 0
        lastUseAt = 0L
        killableBefore = 0; stalledUses = 0; groupDied = false; judgedUses = 0
        attackStartedAt = 0L
        killGroupCutoff = System.currentTimeMillis()  // only this group; later spawns go with the next one
        targetId = -1
        SeaCreatureTracker.clearServerFull()  // re-armed by the next "not enough space" line if still full
    }

    /** Hypixel told us recently that our sea creature cap is full, whatever the tracker counts. */
    private fun serverFull(now: Long): Boolean {
        val at = SeaCreatureTracker.serverFullAt
        return at != 0L && now - at <= SERVER_FULL_FRESH_MS
    }

    private fun finishAttack(nextDelay: Int = delay(250, 500)) {
        attack = null
        targetId = -1
        go(Phase.EQUIP, nextDelay)  // EQUIP swaps back to the rod, then it recasts
    }

    private fun sendReady() {
        if (!chatMode || readySent) return
        val msg = readyMessage.trim()
        if (msg.isEmpty()) return
        readySent = true
        ClientUtils.sendCommand("/pc $msg")
    }

    private fun resetKillerWait() {
        killerWaitStart = 0L
        partyCapSince = 0L
    }

    /** Client tick (from Fish5). No-op unless running. */
    fun tick() {
        if (!running || MacroStateManager.getCurrentState() != MacroState.State.RUNNING) return
        val p = mc.player ?: return
        val level = mc.level ?: return
        if (mc.gui.screen() != null) return  // a menu/chat is open; the GUI failsafe deals with unexpected ones
        val now = System.currentTimeMillis()

        SeaCreatureTracker.want(SeaCreatureTracker.WANT_MACRO, inParty)
        // watched every tick, not just while waiting: a sharer can swap just before we reach our limit
        if (isKiller && waitFor == WaitFor.WEAPON_SWAP) {
            PartyWeaponWatch.setWeapon(lootshareWeapon)
            PartyWeaponWatch.tick()
        }
        // something else (auto sell) asked for the loop to hold at its next safe point
        if (MacroPause.requested && MacroPause.parked) return
        if (attack == null && now >= killCooldownUntil) decideAttack(now)

        if (now < actAt) return

        // an attack is due: take it as soon as nothing is mid-way (reel the line in first)
        if (attack != null && (phase == Phase.EQUIP || phase == Phase.AIM || phase == Phase.CAST ||
                (phase == Phase.ROTATE && !RotationManager.isRotating()) || phase == Phase.FISHING)) {
            if (phase == Phase.FISHING && p.fishing != null) return reel()
            return go(Phase.ATK_SWAP, atkStep())
        }

        when (phase) {
            Phase.EQUIP -> {
                // line in, nothing to attack: the one place it's safe to hand over to a menu
                if (MacroPause.requested) { MacroPause.parked = true; return }
                val slot = RodDetector.findHotbarRod(p)
                if (slot < 0) return fail("no fishing rod in your hotbar.")
                if (FailsafeManager.getCurrentSelectedSlot(mc) != slot) {
                    FailsafeManager.selectHotbarSlot(mc, slot)
                    go(Phase.AIM, Fishing.rodRecastMs())
                } else {
                    go(Phase.AIM)
                }
            }

            Phase.AIM -> {
                if (RotationManager.isRotating()) return
                val plan = CastAim.plan(p, level, Fishing.aimMode, Fishing.fluidKind, Fishing.randomizeAim, Fishing.aimOffset)
                if (plan == null) {
                    if (++failedPlans >= 3) return fail("no spot to cast to (${CastAim.lastError}).")
                    go(Phase.AIM, delay(400, 800))
                    return
                }
                failedPlans = 0
                planYaw = plan.yaw; planPitch = plan.pitch
                val turn = Math.max(Math.abs(Mth.wrapDegrees(plan.yaw - p.yRot)), Math.abs(plan.pitch - p.xRot))
                if (CastAim.lastSameBlock && turn <= CAST_WHILE_TURNING_DEG) {
                    // recasting to the same block: the anti-AFK nudge is a pixel or few, so cast while it
                    // is still turning instead of waiting for it (every point of the nudge is inside the block)
                    RotationManager.rotateToYawPitch(mc, plan.yaw, plan.pitch, delay(90, 170).toLong())
                    go(Phase.CAST, delay(13, 40))
                } else {
                    RotationManager.rotateToYawPitch(mc, plan.yaw, plan.pitch, delay(250, 450).toLong())
                    go(Phase.ROTATE)
                }
            }

            Phase.ROTATE -> {
                if (RotationManager.isRotating()) return
                val dy = Math.abs(Mth.wrapDegrees(p.yRot - planYaw))
                val dp = Math.abs(p.xRot - planPitch)
                // the rotation didn't run or got cut short: plan again rather than cast off target
                if (dy > AIM_TOLERANCE_DEG || dp > AIM_TOLERANCE_DEG) go(Phase.AIM, delay(150, 300))
                else go(Phase.CAST, delay(30, 90))
            }

            Phase.CAST -> {
                if (RodDetector.heldRod(p) == null) return go(Phase.EQUIP, delay(150, 300))
                ClientUtils.performUseClickNow()
                go(Phase.WAIT_HOOK)
            }

            Phase.WAIT_HOOK -> {
                if (p.fishing != null) {
                    failedCasts = 0
                    go(Phase.FISHING)
                } else if (now - phaseAt > HOOK_SPAWN_TIMEOUT_MS) {
                    if (++failedCasts >= 4) return fail("the rod didn't cast after several tries.")
                    go(Phase.EQUIP, delay(300, 700))
                }
            }

            Phase.FISHING -> {
                val hook = p.fishing ?: return go(Phase.EQUIP, delay(300, 700))  // line gone (reeled by hand, broke)
                val inFluid = hook.isInWater || hook.isInLava || !level.getFluidState(hook.blockPosition()).isEmpty
                val waited = now - phaseAt
                if (!inFluid) {
                    if (waited > NOT_IN_FLUID_TIMEOUT_MS) reel()
                    return
                }
                if (waited > NO_BITE_TIMEOUT_MS) return reel()
                // confirm the bite over 2 ticks, react on the confirming tick, latch so one bite = one click
                if (biteVisible(level, hook)) {
                    if (!biteLatched && ++biteTicks >= 2) { biteLatched = true; reel() }
                } else {
                    biteTicks = 0
                }
            }

            Phase.REEL -> {
                if (p.fishing == null) return go(if (attack != null) Phase.ATK_SWAP else Phase.EQUIP, recastDelay())
                if (now - phaseAt > REEL_TIMEOUT_MS) {
                    // the reel click didn't register; click again (only while the bobber is still out)
                    if (++reelClicks >= 3) return fail("couldn't reel the line in.")
                    ClientUtils.performUseClickNow()
                    phaseAt = now
                }
            }

            Phase.ATK_SWAP -> {
                if (attack == null) return go(Phase.EQUIP)
                if (p.inventory.getItem(attackSlot).isEmpty) {
                    Msg.chat("§cNo weapon in hotbar slot ${attackSlot + 1}, skipping the attack.")
                    killCooldownUntil = now + KILL_RETRY_COOLDOWN_MS
                    return finishAttack(0)
                }
                if (attackStartedAt == 0L) attackStartedAt = now
                FailsafeManager.selectHotbarSlot(mc, attackSlot)
                go(Phase.ATK_AIM, atkStep())
            }

            Phase.ATK_AIM -> {
                if (RotationManager.isRotating()) return
                val rnd = ThreadLocalRandom.current()
                when (attackRotation) {
                    CombatRotation.NONE -> go(Phase.ATK_USE, useStep())
                    CombatRotation.DOWN -> {
                        if (p.xRot >= 80f) return go(Phase.ATK_USE, useStep())  // already looking down
                        RotationManager.rotateToYawPitch(mc, p.yRot + rnd.nextFloat(-4f, 4f), rnd.nextFloat(82f, 89f), delay(250, 400).toLong())
                        go(Phase.ATK_ROTATE)
                    }
                    CombatRotation.TARGET -> {
                        targetId = SeaCreatureTracker.nearestKillableId(p.x, p.y, p.z, includeOthers = attack == Attack.HIT || attackIncludesOthers,
                            ignoreRunic = attackSkipsRunic, spawnedBy = if (attack == Attack.KILL) killGroupCutoff else Long.MAX_VALUE)
                        val target = if (targetId >= 0) level.getEntity(targetId) else null
                        if (target == null) {
                            // nothing tracked to aim at: a hit still goes off (splash), and so does a kill's first
                            // use (Hypixel may say we're full of creatures the tracker missed); otherwise the kill is done
                            if (attack == Attack.HIT || attackUses == 0) go(Phase.ATK_USE, useStep()) else finishAttack()
                            return
                        }
                        // a random point within 1 block of the worm, not its exact centre: no welded aim
                        val c = target.boundingBox.center
                        val r = TARGET_AIM_RADIUS * Math.cbrt(rnd.nextDouble())
                        val theta = rnd.nextDouble(0.0, Math.PI * 2)
                        val cosPhi = rnd.nextDouble(-1.0, 1.0)
                        val sinPhi = Math.sqrt(1.0 - cosPhi * cosPhi)
                        RotationManager.initiateRotation(mc, net.minecraft.world.phys.Vec3(
                            c.x + r * sinPhi * Math.cos(theta), c.y + r * cosPhi, c.z + r * sinPhi * Math.sin(theta)),
                            delay(250, 400).toLong(), 1.0f)
                        go(Phase.ATK_ROTATE)
                    }
                }
            }

            Phase.ATK_ROTATE -> {
                if (!RotationManager.isRotating()) go(Phase.ATK_USE, useStep())
            }

            Phase.ATK_USE -> {
                val kind = attack ?: return go(Phase.EQUIP)
                if (FailsafeManager.getCurrentSelectedSlot(mc) != attackSlot) return go(Phase.ATK_SWAP)
                if (kind == Attack.KILL) {
                    if (attackUses > 0) {
                        // Wait for the last use to land before judging it: Hypixel reports the hit ("Your Spirit
                        // Sceptre hit 10 enemies") a few hundred ms after the click, and the deaths after that.
                        // Clicking again sooner always fired one use too many.
                        val hitAt = SeaCreatureTracker.lastAbilityHitAt
                        val landed = hitAt >= lastUseAt && now - hitAt >= postHitWaitMs
                        if (!landed && now - lastUseAt < USE_RESULT_TIMEOUT_MS) return
                    }
                    SeaCreatureTracker.refreshNow()  // don't wait for the throttled check: stop as soon as they're dead
                    val left = SeaCreatureTracker.killable(attackIncludesOthers, attackSkipsRunic, killGroupCutoff)
                    // at least one use, so a kill Hypixel asked for (tracker shows none) still splashes once
                    if (attackUses > 0 && left == 0) {
                        Msg.dbg("Kill: done in ${(now - attackStartedAt) / 1000.0} s, $attackUses use(s)")
                        return finishAttack()
                    }
                    if (attackUses > 0 && attackUses != judgedUses) {  // once per use (re-aiming re-enters here)
                        judgedUses = attackUses
                        if (left < killableBefore) { groupDied = true; stalledUses = 0 } else stalledUses++
                        // the rest of the group died and these outlived two more hits: runic that detection missed
                        if (attackSkipsRunic && groupDied && stalledUses >= RUNIC_STALL_USES) {
                            val n = SeaCreatureTracker.markRemainingRunic(attackIncludesOthers, killGroupCutoff)
                            Msg.dbg("Kill: $n left outlived $stalledUses hits after the rest died, treated as runic")
                            return finishAttack()
                        }
                    }
                    killableBefore = left
                    if (now - attackStartedAt > KILL_TIMEOUT_MS) {
                        Msg.chat("§eCouldn't finish the kill in ${KILL_TIMEOUT_MS / 1000} s §7(out of reach?), back to fishing.")
                        killCooldownUntil = now + KILL_RETRY_COOLDOWN_MS
                        return finishAttack()
                    }
                }
                if (attackRotation == CombatRotation.TARGET && targetId >= 0) {
                    val target = level.getEntity(targetId)
                    // target died / aim no longer passes within 1 block of it: aim again (rotation and use must agree)
                    if (target == null || (target is LivingEntity && target.isDeadOrDying) || !onCrosshair(p, target.boundingBox.center.x,
                            target.boundingBox.center.y, target.boundingBox.center.z)) {
                        return go(Phase.ATK_AIM, atkStep())
                    }
                }
                ClientUtils.performUseClickNow()
                attackUses++
                lastUseAt = now
                postHitWaitMs = delay(330, 450).toLong()
                if (kind == Attack.HIT) return finishAttack()
                go(Phase.ATK_USE, useStep())  // then wait for the hit to land (above) before the next use
            }
        }
    }

    // -- when to hit / kill ------------------------------------------------------------------------

    private fun decideAttack(now: Long) {
        val threshold = Fishing.scThreshold
        val ours = SeaCreatureTracker.count
        val oursKillable = SeaCreatureTracker.killable(false, ignoreRunic)
        val atCap = ours >= threshold && oursKillable > 0
        val oldest = SeaCreatureTracker.oldestKillableSpawnAt(ignoreRunic)
        val despawnDue = Fishing.despawnTimer && oldest != 0L && now - oldest >= Fishing.killAfterMs
        // Hypixel says we're full but the tracker disagrees (it missed a spawn, or counted a survivor as
        // dead): kill anyway, or we'd fish forever at a cap no new creature can spawn into. Runic ones are
        // included in that kill when nothing else is left, since otherwise they'd hold the cap.
        val full = serverFull(now) && !atCap
        val fullSkipRunic = ignoreRunic && oursKillable > 0
        if (ours < threshold && !full) readySent = false

        when (partyMode) {
            PartyMode.NONE -> {
                if (!atCap && !despawnDue && !full) { soloKillDueAt = 0L; return }
                if (soloKillDueAt == 0L) {
                    soloKillDueAt = now + delay(attackDelay.low, attackDelay.high)
                }
                if (now >= soloKillDueAt) {
                    soloKillDueAt = 0L
                    if (full) {
                        if (lastFullKillAt != 0L && now - lastFullKillAt < FULL_KILL_REPEAT_MS) {
                            Msg.chat("§eStill at the sea creature cap after killing §7(out of reach?)§e, trying again in ${KILL_RETRY_COOLDOWN_MS / 1000} s.")
                            lastFullKillAt = 0L
                            SeaCreatureTracker.clearServerFull()
                            killCooldownUntil = now + KILL_RETRY_COOLDOWN_MS
                            return
                        }
                        lastFullKillAt = now
                    }
                    Msg.chat(when {
                        atCap -> "§eSC threshold §f($ours/$threshold)§e, killing."
                        full -> "§eHypixel says your sea creature cap is full §7(tracked $ours/$threshold)§e, killing."
                        else -> "§eDespawn timer, killing."
                    })
                    requestAttack(Attack.KILL, weaponSlot, includeOthers = false, skipRunic = if (full) fullSkipRunic else ignoreRunic)
                }
            }

            PartyMode.LOOTSHARE -> {
                if (ours >= threshold) {
                    if (!ownHitLatched) {
                        ownHitLatched = true
                        lootWaitStart = now
                        sendReady()
                        Msg.chat("§eAt your SC threshold §f($ours/$threshold)§e, hitting once.")
                        requestAttack(Attack.HIT, weaponSlot, includeOthers = true)
                        return
                    }
                } else {
                    ownHitLatched = false
                    lootWaitStart = 0L
                }

                val killer = killerName.trim()
                val killerScs = when {
                    killer.isNotEmpty() -> SeaCreatureTracker.countFor(killer)
                    Party.members.isNotEmpty() -> maxMemberCount()
                    else -> SeaCreatureTracker.maxOtherCount()
                }
                if (killerScs >= threshold) {
                    if (!killerHitLatched) {
                        killerHitLatched = true
                        Msg.chat("§e${killer.ifEmpty { "Killer" }} is at the SC threshold §f($killerScs/$threshold)§e, hitting once.")
                        requestAttack(Attack.HIT, weaponSlot, includeOthers = true)
                        return
                    }
                } else {
                    killerHitLatched = false
                }

                // the killer never came: kill ours with the override weapon
                if (override && oursKillable > 0) {
                    val waitedTooLong = lootWaitStart != 0L && now - lootWaitStart >= (overrideAfter * 1000).toLong()
                    if (waitedTooLong || despawnDue) {
                        Msg.chat("§eOverride: ${if (despawnDue) "despawn timer" else "waited ${overrideAfter.toInt()} s"}, killing ours.")
                        lootWaitStart = now
                        requestAttack(Attack.KILL, overrideWeaponSlot, includeOthers = false)
                    }
                }
            }

            PartyMode.KILLER -> {
                if (!atCap && !despawnDue && !full) { resetKillerWait(); return }
                if (killerWaitStart == 0L) {
                    killerWaitStart = now
                    partyCapDelayMs = delay(2000, 2600)
                    swapReactMs = delay(250, 650)
                    lastSwapReport = now
                    sendReady()
                    Msg.chat("§eAt your limit §f($ours/$threshold)§e, waiting for the party (${waitLabel()}).")
                }
                val reason: String? = when {
                    despawnDue -> "despawn timer"
                    override && now - killerWaitStart >= (overrideAfter * 1000).toLong() -> "override"
                    waitFor == WaitFor.FIXED_DELAY ->
                        if (now - killerWaitStart >= (killDelay * 1000).toLong()) "${killDelay.toInt()} s delay" else null
                    waitFor == WaitFor.CHAT_MESSAGE ->
                        if (killMessageAt != 0L && killMessageAt >= killerWaitStart - CHAT_MESSAGE_MAX_AGE_MS &&
                            now - killMessageAt >= 300L) "kill message" else null
                    waitFor == WaitFor.WEAPON_SWAP -> {
                        val since = killerWaitStart - SWAP_LOOKBACK_MS
                        val back = PartyWeaponWatch.allSwappedBackAt(since)
                        if (back == 0L && now - lastSwapReport >= SWAP_REPORT_MS) {
                            lastSwapReport = now
                            Msg.chat("§7Waiting on weapon swaps: §f${PartyWeaponWatch.describe(since).ifEmpty { "no party members known (/p list)" }}")
                        }
                        // react a moment after the last one is back on their rod, like a person would
                        if (back != 0L && now - back >= swapReactMs) "party swapped back" else null
                    }
                    else -> {
                        if (partyAtCap(threshold)) {
                            if (partyCapSince == 0L) partyCapSince = now
                            if (now - partyCapSince >= partyCapDelayMs) "party at cap" else null
                        } else {
                            partyCapSince = 0L
                            null
                        }
                    }
                }
                if (reason != null) {
                    Msg.chat("§eKilling §7($reason).")
                    resetKillerWait()
                    killMessageAt = 0L
                    requestAttack(Attack.KILL, weaponSlot, includeOthers = true)
                }
            }
        }
    }

    private fun waitLabel() = when (waitFor) {
        WaitFor.PARTY_CAP -> "party at cap + 2 s"
        WaitFor.FIXED_DELAY -> "${killDelay.toInt()} s"
        WaitFor.CHAT_MESSAGE -> "'${killMessage.trim()}' in party chat"
        WaitFor.WEAPON_SWAP -> "party swaps to ${lootshareWeapon.trim()} and back"
    }

    // every party member has the threshold alive (members unknown: anyone fishing near us does)
    private fun partyAtCap(threshold: Int): Boolean {
        val members = Party.members
        if (members.isEmpty()) {
            if (!warnedNoMembers) {
                warnedNoMembers = true
                Msg.chat("§eNo party members known §7(not in a party, or /p list not read yet). Using anyone fishing nearby.")
            }
            return SeaCreatureTracker.maxOtherCount() >= threshold
        }
        for (m in members) if (SeaCreatureTracker.countFor(m) < threshold) return false
        return true
    }

    private fun maxMemberCount(): Int {
        var best = 0
        for (m in Party.members) { val n = SeaCreatureTracker.countFor(m); if (n > best) best = n }
        return best
    }

    // -- helpers -----------------------------------------------------------------------------------

    private fun reel() {
        ClientUtils.performUseClickNow()
        go(Phase.REEL)
    }

    // does the look line pass within TARGET_RADIUS blocks of (x, y, z), in front of the eyes?
    private fun onCrosshair(p: LocalPlayer, x: Double, y: Double, z: Double): Boolean {
        val eye = p.eyePosition
        val dx = x - eye.x; val dy = y - eye.y; val dz = z - eye.z
        val distSq = dx * dx + dy * dy + dz * dz
        if (distSq <= TARGET_RADIUS * TARGET_RADIUS) return true
        val look = p.getViewVector(1f)
        val along = dx * look.x + dy * look.y + dz * look.z
        if (along <= 0.0) return false  // behind us
        return distSq - along * along <= TARGET_RADIUS * TARGET_RADIUS
    }

    // Hypixel shows a "!!!" armor stand right above the bobber on a bite. Only look straight over our
    // own bobber so a party member's bite next to it doesn't count.
    private fun biteVisible(level: Level, hook: FishingHook): Boolean {
        val box = AABB(hook.x - 0.5, hook.y - 0.5, hook.z - 0.5, hook.x + 0.5, hook.y + 2.5, hook.z + 0.5)
        return level.getEntitiesOfClass(ArmorStand::class.java, box, BITE_STAND).isNotEmpty()
    }
}
