package com.fish5.features.impl.misc

import com.fish5.automation.Menus
import com.fish5.automation.Msg
import com.fish5.clickgui.settings.impl.ActionSetting
import com.fish5.clickgui.settings.impl.BooleanSetting
import com.fish5.clickgui.settings.impl.NumberSetting
import com.fish5.clickgui.settings.impl.StringListSetting
import com.fish5.config.ConfigHelpers
import com.fish5.failsafe.FailsafeManager
import com.fish5.features.Category
import com.fish5.features.MacroManager
import com.fish5.features.MacroPause
import com.fish5.features.Module
import com.fish5.features.impl.fishing.RodDetector
import com.fish5.macro.MacroState
import com.fish5.macro.MacroStateManager
import com.fish5.sb.SkyblockData
import com.fish5.utils.ClientUtils
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ItemStack

/**
 * Auto sell, after aether's AutoSellManager. When the inventory fills up:
 * 1. NPC: open `/boostercookie` (with the Cookie Buff that menu sells to the NPC) and shift-click every
 *    item on the user's list out of the inventory. The list is for NPC selling only; no preset list.
 * 2. Bazaar: open `/bz`, click **Sell Inventory Now** and confirm, which insta-sells every bazaar item
 *    left in the inventory. Never the **Sell Sacks Now** button: sacks are left alone.
 * Then close the menu and carry on.
 *
 * A running macro is never interrupted mid-cast or mid-fight: we ask it to hold ([MacroPause]) and
 * only open the menu once it has parked with the line in. The macro state is RECOVERING and the GUI
 * failsafe is told to expect the menu while we're in it. Clicks go through the open screen, spaced
 * by [ClientUtils.getGuiClickDelayMs].
 */
object AutoSell : Module(
    name = "Auto Sell",
    category = Category.MODULES,
    description = "When your inventory fills up: sell your NPC list to the NPC (via /boostercookie), then " +
        "insta-sell the rest of your inventory on the bazaar (never your sacks).",
) {

    // keeps its old name so saved lists still load; it's the NPC-only list
    private val items = registerSetting(StringListSetting(
        "Items to sell",
        desc = "NPC sell list: these go to the NPC, not the bazaar. Part of an item's name (case doesn't " +
            "matter) or its SkyBlock id. Every matching stack in your inventory is sold. Fishing rods are never sold.",
        placeholder = "Item name, then Enter",
    ))
    private val bazaarSell by BooleanSetting(
        "Bazaar insta-sell", true,
        desc = "After the NPC sell, use the bazaar's Sell Inventory Now to insta-sell every bazaar item in your " +
            "inventory. Sacks are never sold. Needs a Cookie Buff to open /bz away from the hub.",
    )
    private val fullAt by NumberSetting<Double>(
        "Inventory full at", 85.0, 30.0, 100.0, 5.0, unit = "%",
        desc = "Sell once this much of your inventory (36 slots) is used.",
    )
    private val onlyWhileMacro by BooleanSetting(
        "Only while a macro runs", true,
        desc = "Off: also sell when you're playing by hand (it opens a menu on you, so careful).",
    )
    private val addHeld by ActionSetting("Add held item", desc = "Add the item in your hand to the list.") {
        val stack = mc.player?.mainHandItem
        if (stack == null || stack.isEmpty) Msg.chat("§eHold the item you want to add.")
        else {
            val name = SkyblockData.stripColors(stack.hoverName.string).trim()
            if (items.add(name)) Msg.chat("§aAuto Sell: added §f$name§a.") else Msg.chat("§e$name is already on the list.")
        }
    }
    private val sellNowAction by ActionSetting("Sell now", desc = "Run the NPC + bazaar sell right now.") { sellNow() }

    private enum class State {
        IDLE, WAIT_PARK, OPEN, WAIT_MENU, SELLING,
        BZ_OPEN, BZ_WAIT_MENU, BZ_CLICK_SELL, BZ_WAIT_CONFIRM, BZ_CLICK_CONFIRM, BZ_WAIT_SOLD,
        CLOSING, RESUMING,
    }

    private const val CHECK_EVERY_MS = 1000L
    private const val PARK_TIMEOUT_MS = 30_000L
    private const val MENU_TIMEOUT_MS = 5000L
    private const val SELL_TIMEOUT_MS = 30_000L
    private const val COOLDOWN_MS = 30_000L
    private const val NO_MENU_COOLDOWN_MS = 5 * 60_000L
    private const val COIN_LIMIT_COOLDOWN_MS = 60 * 60_000L
    private const val MENU_TITLE = "booster cookie"
    private const val BZ_SELL_BUTTON = "sell inventory now"
    private const val BZ_CONFIRM_BUTTON = "selling whole inventory"
    private const val BZ_SOLD_WAIT_MS = 4000L
    private const val NOTHING_SOLD_COOLDOWN_MS = 5 * 60_000L
    private var lastSoldAt = 0L

    private var state = State.IDLE
    /** We opened a container (either menu) and must close it on the way out. */
    private var menuOpen = false
    private var bzSold = 0
    private var bzSoldSeen = false
    private var stateAt = 0L
    private var actAt = 0L
    private var nextCheckAt = 0L
    private var cooldownUntil = 0L
    private var pausedMacro = false
    private var sold = 0
    private var stopListenerAdded = false

    // lowercased list + ids, rebuilt only when the list changes
    private val names = ArrayList<String>()
    private val ids = ArrayList<String>()
    private var builtFrom = -1

    private fun go(next: State, delayMs: Long = 0L) {
        state = next
        stateAt = System.currentTimeMillis()
        actAt = stateAt + delayMs
    }

    private fun delay(min: Int, max: Int) = ConfigHelpers.getRandomizedDelay(min, max).toLong()

    /** Sell now, whether or not the inventory is full (button, /f5 sell). */
    fun sellNow() {
        if (state != State.IDLE) { Msg.chat("§eAuto Sell is already selling."); return }
        if (items.value.isEmpty() && !bazaarSell) { Msg.chat("§eAuto Sell: add some items to sell first."); return }
        begin()
    }

    /** NPC sell first if anything on the list is in the inventory, else straight to the bazaar. */
    private fun firstStep(): State = when {
        findSellable(null) >= 0 -> State.OPEN
        bazaarSell -> State.BZ_OPEN
        else -> State.RESUMING
    }

    private fun begin() {
        sold = 0
        bzSold = 0
        bzSoldSeen = false
        menuOpen = false
        if (!stopListenerAdded) {
            stopListenerAdded = true
            MacroStateManager.addStopListener { if (state != State.IDLE) abort(null) }
        }
        if (MacroManager.running != null) {
            Msg.dbg("Auto Sell: waiting for the macro to reel in")
            MacroPause.request()
            go(State.WAIT_PARK)
        } else {
            go(firstStep(), delay(100, 250))
        }
    }

    /** Client tick (from Fish5). Idle cost: one inventory count per second while enabled. */
    fun tick() {
        val player = mc.player
        if (player == null || mc.level == null) {
            if (state != State.IDLE) abort(null)
            return
        }
        val now = System.currentTimeMillis()
        if (state == State.IDLE) {
            if (!enabled || now < nextCheckAt || now < cooldownUntil) return
            nextCheckAt = now + CHECK_EVERY_MS
            if ((items.value.isEmpty() && !bazaarSell) || mc.gui.screen() != null) return
            if (onlyWhileMacro && MacroManager.running == null) return
            if (MacroPause.requested) return  // something else (auto mode) has the macro parked right now
            if (!SkyblockData.isOnSkyblock(mc)) return
            val used = usedSlots()
            if (used * 100.0 / 36.0 < fullAt) return
            if (!bazaarSell && findSellable(null) < 0) { nextCheckAt = now + 10_000L; return }  // full of things we don't sell
            Msg.chat("§eInventory $used/36, auto selling.")
            begin()
            return
        }
        if (now < actAt) return
        val screen = mc.gui.screen()
        when (state) {
            State.WAIT_PARK -> {
                if (MacroManager.running == null) { MacroPause.release(); go(firstStep(), delay(100, 250)); return }
                if (MacroPause.parked) {
                    pausedMacro = true
                    MacroStateManager.setState(MacroState.State.RECOVERING)
                    go(firstStep(), delay(200, 500))
                } else if (now - stateAt > PARK_TIMEOUT_MS) {
                    abort("the macro never got to a safe point")
                }
            }
            State.OPEN -> {
                FailsafeManager.setExpectingGui(true)
                menuOpen = true
                ClientUtils.sendCommand("/boostercookie")
                go(State.WAIT_MENU)
            }
            State.WAIT_MENU -> {
                if (isSellMenu(screen)) {
                    go(State.SELLING, ClientUtils.getGuiClickDelayMs(true))
                } else if (now - stateAt > MENU_TIMEOUT_MS) {
                    abort("the Booster Cookie menu didn't open (it needs an active Cookie Buff)", NO_MENU_COOLDOWN_MS)
                }
            }
            State.SELLING -> {
                if (screen !is AbstractContainerScreen<*> || !isSellMenu(screen)) { finish(); return }  // closed under us
                val idx = if (now - stateAt > SELL_TIMEOUT_MS) -1 else findSellable(screen)
                if (idx < 0) {
                    if (bazaarSell) {
                        screen.onClose()  // the same path as pressing Esc
                        go(State.BZ_OPEN, delay(400, 800))
                    } else {
                        go(State.CLOSING, delay(150, 350))
                    }
                    return
                }
                ClientUtils.performSlotClick(screen, idx, 0, ContainerInput.QUICK_MOVE)
                sold++
                actAt = now + ClientUtils.getGuiClickDelayMs(false)
            }
            State.BZ_OPEN -> {
                if (screen != null) { if (now - stateAt > MENU_TIMEOUT_MS) abort("a menu stayed open"); return }
                FailsafeManager.setExpectingGui(true)
                menuOpen = true
                ClientUtils.sendCommand("/bz")
                go(State.BZ_WAIT_MENU)
            }
            State.BZ_WAIT_MENU -> {
                val idx = if (screen is AbstractContainerScreen<*>) Menus.findSlotByName(mc, BZ_SELL_BUTTON, false) else -1
                if (idx >= 0) {
                    if (bzNothingToSell(idx)) { go(State.CLOSING, delay(200, 450)); return }
                    go(State.BZ_CLICK_SELL, ClientUtils.getGuiClickDelayMs(true))
                } else if (now - stateAt > MENU_TIMEOUT_MS) {
                    abort("the bazaar didn't open on a page with Sell Inventory Now (needs a Cookie Buff off the hub)",
                        NO_MENU_COOLDOWN_MS)
                }
            }
            State.BZ_CLICK_SELL -> {
                val idx = if (screen is AbstractContainerScreen<*>) Menus.findSlotByName(mc, BZ_SELL_BUTTON, false) else -1
                if (screen !is AbstractContainerScreen<*> || idx < 0) { abort("the bazaar closed"); return }
                ClientUtils.performSlotClick(screen, idx, 0, ContainerInput.PICKUP)
                go(State.BZ_WAIT_CONFIRM)
            }
            State.BZ_WAIT_CONFIRM -> {
                if (bzSoldSeen) { go(State.BZ_WAIT_SOLD); return }  // sold without a confirm page
                if (screen !is AbstractContainerScreen<*>) { go(State.BZ_WAIT_SOLD); return }  // Hypixel closed it
                // still on the main page: wait for the confirm page so we never click there twice
                if (Menus.findSlotByName(mc, BZ_SELL_BUTTON, false) < 0 && findBzConfirm() >= 0) {
                    go(State.BZ_CLICK_CONFIRM, ClientUtils.getGuiClickDelayMs(false))
                } else if (now - stateAt > MENU_TIMEOUT_MS) {
                    Msg.LOG.warn("[Auto Sell] no bazaar confirm button, menu: {}", Menus.dumpContainer(mc))
                    abort("the bazaar's sell confirm page didn't show up")
                }
            }
            State.BZ_CLICK_CONFIRM -> {
                val idx = findBzConfirm()
                if (screen !is AbstractContainerScreen<*> || idx < 0) { abort("the bazaar confirm page closed"); return }
                ClientUtils.performSlotClick(screen, idx, 0, ContainerInput.PICKUP)
                go(State.BZ_WAIT_SOLD)
            }
            State.BZ_WAIT_SOLD -> {
                // one "[Bazaar] Sold" line per product: wait until they stop coming
                val quiet = bzSoldSeen && now - lastSoldAt > 800
                if (quiet || now - stateAt > BZ_SOLD_WAIT_MS) go(State.CLOSING, delay(150, 400))
            }
            State.CLOSING -> {
                if (menuOpen && screen is AbstractContainerScreen<*>) screen.onClose()  // the same path as pressing Esc
                go(State.RESUMING, delay(300, 600))
            }
            State.RESUMING -> finish()
            State.IDLE -> {}
        }
    }

    private fun finish() {
        if (sold == 0 && bzSold == 0) {
            Msg.chat("§7Auto Sell: nothing to sell.")
            release(NOTHING_SOLD_COOLDOWN_MS)  // full of unsellables: don't reopen menus every 30 s
            return
        }
        val parts = ArrayList<String>(2)
        if (sold > 0) parts.add("$sold stack${if (sold == 1) "" else "s"} to the NPC")
        if (bzSold > 0) parts.add("$bzSold item${if (bzSold == 1) "" else "s"} on the bazaar")
        Msg.chat("§aAuto Sell: sold ${parts.joinToString(", ")}.")
        release(COOLDOWN_MS)
    }

    private fun abort(why: String?, cooldown: Long = COOLDOWN_MS) {
        if (why != null) Msg.chat("§cAuto Sell stopped: §7$why")
        val screen = mc.gui.screen()
        if (menuOpen && screen is AbstractContainerScreen<*>) screen.onClose()
        release(cooldown)
    }

    private fun release(cooldown: Long) {
        menuOpen = false
        FailsafeManager.setExpectingGui(false)
        if (pausedMacro && MacroStateManager.getCurrentState() == MacroState.State.RECOVERING) {
            MacroStateManager.setState(MacroState.State.RUNNING)
        }
        pausedMacro = false
        MacroPause.release()
        cooldownUntil = System.currentTimeMillis() + cooldown
        state = State.IDLE
    }

    /** Every non-actionbar chat line. */
    fun onChat(raw: String) {
        if (state.ordinal >= State.BZ_CLICK_SELL.ordinal && state.ordinal <= State.BZ_WAIT_SOLD.ordinal &&
            raw.contains("Sold") && SkyblockData.stripColors(raw).startsWith("[Bazaar] Sold")) {
            bzSold++
            bzSoldSeen = true
            lastSoldAt = System.currentTimeMillis()
            return
        }
        if (!raw.contains("daily limit of coins")) return
        if (!SkyblockData.stripColors(raw).contains("You've reached the daily limit of coins you may earn from NPC shops")) return
        if (state != State.IDLE) abort("NPC daily coin limit reached, trying again in an hour.", COIN_LIMIT_COOLDOWN_MS)
        else cooldownUntil = System.currentTimeMillis() + COIN_LIMIT_COOLDOWN_MS
    }

    /** The Sell Inventory Now button says so when nothing in the inventory sells on the bazaar. */
    private fun bzNothingToSell(slot: Int): Boolean {
        for (line in Menus.loreLines(Menus.stackAt(mc, slot))) {
            val l = line.lowercase()
            if (l.contains("don't have anything") || l.contains("nothing to sell")) return true
        }
        return false
    }

    /**
     * The confirm button on the page after Sell Inventory Now. Matched by name, with a lore fallback;
     * anything mentioning sacks or cancel is never picked.
     */
    private fun findBzConfirm(): Int {
        val byName = Menus.findSlotByName(mc, BZ_CONFIRM_BUTTON, false)
        if (byName >= 0) return byName
        val byLore = Menus.findSlotByLore(mc, "click to confirm")
        if (byLore < 0) return -1
        val name = Menus.slotName(mc, byLore)?.lowercase() ?: return -1
        return if (name.contains("sack") || name.contains("cancel")) -1 else byLore
    }

    private fun isSellMenu(screen: Any?): Boolean =
        screen is AbstractContainerScreen<*> && screen.title.string.lowercase().contains(MENU_TITLE)

    private fun usedSlots(): Int {
        val inv = mc.player?.inventory ?: return 0
        var used = 0
        for (i in 0 until 36) if (!inv.getItem(i).isEmpty) used++
        return used
    }

    /**
     * Index of a listed item: a menu slot (the player inventory part, the menu's last 36 slots) when
     * [screen] is given, else a player inventory slot. -1 if none.
     */
    private fun findSellable(screen: AbstractContainerScreen<*>?): Int {
        rebuildIfChanged()
        if (screen != null) {
            val slots = screen.menu.slots
            for (i in maxOf(0, slots.size - 36) until slots.size) {
                val slot = slots[i]
                if (slot.hasItem() && matches(slot.item)) return i
            }
            return -1
        }
        val inv = mc.player?.inventory ?: return -1
        for (i in 0 until 36) if (matches(inv.getItem(i))) return i
        return -1
    }

    private fun matches(stack: ItemStack): Boolean {
        if (stack.isEmpty || RodDetector.isRod(stack)) return false
        val id = SkyblockData.skyblockId(stack)
        if (id != null) for (want in ids) if (id == want) return true
        val name = SkyblockData.stripColors(stack.hoverName.string).lowercase()
        for (want in names) if (name.contains(want)) return true
        return false
    }

    private fun rebuildIfChanged() {
        val list = items.value
        val sig = list.hashCode()
        if (sig == builtFrom) return
        builtFrom = sig
        names.clear(); ids.clear()
        for (s in list) {
            names.add(s.lowercase())
            ids.add(s.uppercase().replace(' ', '_'))
        }
    }
}
