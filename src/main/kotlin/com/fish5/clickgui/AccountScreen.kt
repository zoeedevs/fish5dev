package com.fish5.clickgui

import com.fish5.Fish5
import com.fish5.account.Account
import com.fish5.account.AccountStatus
import com.fish5.account.AccountType
import com.fish5.account.Accounts
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * Plain vanilla account-switcher screen: click a saved account's row to log in as it, add a new
 * one (Cracked / Session token / Microsoft / Refresh token), or remove one with its "X". All
 * state lives in [Accounts] - this screen only renders a snapshot of it and polls every tick
 * while an action (login / Microsoft sign-in / background check) is in flight, since those land
 * asynchronously off the client thread. Opened from the title screen
 * (TitleAccountsButtonMixin); closing returns to [parent].
 */
class AccountScreen(private val parent: Screen?) : Screen(Component.literal("Accounts")) {

    private var adding: AccountType = AccountType.MICROSOFT
    private var inputBox: EditBox? = null
    private var statusWidget: StringWidget? = null
    private var lastStatus: String = ""
    private var lastBusy = false
    private var lastCount = -1

    override fun init() {
        val f: Font = Fish5.mc.font
        val bw = 260
        val x = width / 2 - bw / 2
        var y = 24

        addRenderableWidget(StringWidget(x, y, bw, 12, Component.literal("§7Saved accounts"), f))
        y += 16

        val accounts = Accounts.get().list()
        val active = Accounts.get().active()
        if (accounts.isEmpty()) {
            addRenderableWidget(StringWidget(x, y, bw, 12, Component.literal("§7No accounts yet — add one below"), f))
            y += 16
        } else {
            for (account in accounts) {
                val isActive = account == active
                addRenderableWidget(
                    Button.builder(Component.literal(rowLabel(account, isActive))) {
                        if (isActive) resetToLaunch() else switchTo(account)
                    }.bounds(x, y, bw - 24, 20).build(),
                )
                addRenderableWidget(
                    Button.builder(Component.literal("X")) { Accounts.get().remove(account); rebuildWidgets() }
                        .bounds(x + bw - 20, y, 20, 20).build(),
                )
                y += 22
            }
        }
        y += 6

        addRenderableWidget(Button.builder(typeLabel()) { cycleType() }.bounds(x, y, bw, 20).build())
        y += 24

        if (adding == AccountType.MICROSOFT) {
            val busy = Accounts.get().busy()
            addRenderableWidget(
                Button.builder(Component.literal(if (busy) "Waiting for the browser..." else "Sign in with Microsoft")) {
                    Accounts.get().addMicrosoft().whenComplete { _, _ -> Fish5.mc.execute { rebuildWidgets() } }
                    rebuildWidgets()
                }.bounds(x, y, bw, 20).build(),
            )
        } else {
            inputBox = editBox(f, x, y, bw, inputHint())
        }
        y += 24

        val third = (bw - 8) / 3
        addRenderableWidget(Button.builder(Component.literal("Add")) { add() }.bounds(x, y, third, 20).build())
        addRenderableWidget(Button.builder(Component.literal("Check all")) { Accounts.get().checkAll() }.bounds(x + third + 4, y, third, 20).build())
        addRenderableWidget(Button.builder(Component.literal("Close")) { onClose() }.bounds(x + 2 * (third + 4), y, bw - 2 * (third + 4), 20).build())
        y += 26

        statusWidget = addRenderableWidget(StringWidget(x, y, bw, 12, Component.empty(), f))
        refreshStatus()
    }

    private fun editBox(f: Font, x: Int, y: Int, w: Int, hint: String): EditBox {
        val box = EditBox(f, x, y, w, 20, Component.literal("value"))
        box.setMaxLength(4096)
        box.setHint(Component.literal(hint))
        return addRenderableWidget(box)
    }

    private fun rowLabel(account: Account, active: Boolean): String {
        val name = if (account.name().isEmpty()) account.type().label() + " account" else account.name()
        val status = when (Accounts.get().statusOf(account)) {
            AccountStatus.CHECKING -> " §7(checking…)"
            AccountStatus.EXPIRED -> " §c(expired)"
            AccountStatus.ERROR -> " §c(error)"
            else -> ""
        }
        val tag = if (active) "§a[Active] " else ""
        return "$tag$name §8· §7${account.type().label()}$status"
    }

    private fun typeLabel(): Component = Component.literal("Add: ${adding.label()}")

    private fun cycleType() {
        val values = AccountType.values()
        adding = values[(adding.ordinal + 1) % values.size]
        rebuildWidgets()
    }

    private fun inputHint(): String = when (adding) {
        AccountType.CRACKED -> "Username"
        AccountType.SESSION -> "Minecraft access token"
        AccountType.REFRESH_TOKEN -> "Microsoft refresh token"
        AccountType.MICROSOFT -> ""
    }

    private fun switchTo(account: Account) {
        if (Accounts.get().busy()) return
        Accounts.get().login(account).whenComplete { _, _ -> Fish5.mc.execute { rebuildWidgets() } }
        rebuildWidgets()
    }

    /** Clicking the already-active row undoes autoLogin (or any manual switch this session). */
    private fun resetToLaunch() {
        if (Accounts.get().busy()) return
        Accounts.get().resetToLaunch().whenComplete { _, _ -> Fish5.mc.execute { rebuildWidgets() } }
        rebuildWidgets()
    }

    private fun add() {
        if (Accounts.get().busy()) return
        val text = inputBox?.value ?: ""
        val added = when (adding) {
            AccountType.CRACKED -> Accounts.get().addCracked(text)
            AccountType.SESSION -> Accounts.get().addSession(text)
            AccountType.REFRESH_TOKEN -> Accounts.get().addRefreshToken(text)
            AccountType.MICROSOFT -> return
        }
        added.whenComplete { _, _ -> Fish5.mc.execute { rebuildWidgets() } }
        rebuildWidgets()
    }

    private fun refreshStatus() {
        val text = Accounts.get().status()
        lastStatus = text
        lastBusy = Accounts.get().busy()
        lastCount = Accounts.get().list().size
        statusWidget?.message = Component.literal(if (text.isEmpty()) "" else "§7$text")
    }

    override fun tick() {
        super.tick()
        if (Accounts.get().status() != lastStatus || Accounts.get().busy() != lastBusy) {
            refreshStatus()
        }
        if (Accounts.get().list().size != lastCount) {
            rebuildWidgets()
        }
    }

    override fun onClose() {
        // No-op if nothing's waiting on a Microsoft device code; safe to call unconditionally.
        Accounts.get().cancelMicrosoft()
        Fish5.mc.gui.setScreen(parent)
    }
}
