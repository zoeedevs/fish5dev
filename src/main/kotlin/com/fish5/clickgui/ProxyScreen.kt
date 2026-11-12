package com.fish5.clickgui

import com.fish5.Fish5
import com.fish5.features.ModuleManager
import com.fish5.features.impl.opsec.ProxyManager
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Checkbox
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * Plain vanilla proxy-config screen, modelled on the original ru.fiw.proxyserver GuiProxy: a Type
 * toggle, IP:Port / username / password boxes, an Enable checkbox, Apply / Test / Cancel, and a
 * named-preset list with per-row delete. Opened from the Multiplayer menu (MultiplayerProxyButtonMixin).
 * All state lives in [ProxyManager]; closing saves config and returns to [parent].
 */
class ProxyScreen(private val parent: Screen?) : Screen(Component.literal("Proxy Manager")) {

    private var typeButton: Button? = null
    private var ipBox: EditBox? = null
    private var userBox: EditBox? = null
    private var passBox: EditBox? = null
    private var nameBox: EditBox? = null
    private var headerWidget: StringWidget? = null
    private var statusWidget: StringWidget? = null

    /** Last test/save result, shown on-screen (this screen has no player for chat notifications). */
    private var statusText: String = ""

    override fun init() {
        // Route ProxyManager feedback to our on-screen status line instead of (player-less) chat.
        // Marshalled onto the client thread by ProxyManager, so touching the widget here is safe.
        ProxyManager.statusSink = { setStatus(it) }

        val f: Font = Fish5.mc.font
        val bw = 220
        val x = width / 2 - bw / 2
        var y = height / 4 - 8

        // Header doubles as validation: shows the active proxy IP (green when routing is enabled).
        headerWidget = addRenderableWidget(StringWidget(x, y - 30, bw, 20, Component.empty(), f))

        val fields = ProxyManager.activeFields()

        typeButton = addRenderableWidget(
            Button.builder(typeLabel()) { cycleType() }.bounds(x, y, bw, 20).build(),
        )
        y += 24

        ipBox = editBox(f, x, y, bw, "IP:Port", "e.g. 125.1.34.1:2555", fields.hostPort); y += 24
        userBox = editBox(f, x, y, bw, "Username", "username (optional)", fields.user); y += 24
        passBox = editBox(f, x, y, bw, "Password", "password (optional)", fields.pass); y += 24

        addRenderableWidget(
            Checkbox.builder(Component.literal("Enable proxy"), f)
                .pos(x, y)
                .selected(ProxyManager.isRouting())
                .onValueChange { _, v -> ProxyManager.setRouting(v); refreshHeader() }
                .build(),
        )
        y += 24

        val third = (bw - 8) / 3
        addRenderableWidget(Button.builder(Component.literal("Apply")) { apply() }.bounds(x, y, third, 20).build())
        addRenderableWidget(Button.builder(Component.literal("Test")) { test() }.bounds(x + third + 4, y, third, 20).build())
        addRenderableWidget(Button.builder(Component.literal("Cancel")) { onClose() }.bounds(x + 2 * (third + 4), y, bw - 2 * (third + 4), 20).build())
        y += 26

        nameBox = editBox(f, x, y, bw - 64, "Preset name", "preset name (optional)", "")
        addRenderableWidget(Button.builder(Component.literal("Save")) { save() }.bounds(x + bw - 60, y, 60, 20).build())
        y += 26

        for ((name, display) in ProxyManager.presets()) {
            addRenderableWidget(
                Button.builder(Component.literal(name)) { ProxyManager.loadPreset(display); rebuildWidgets() }
                    .bounds(x, y, bw - 24, 20).build(),
            )
            addRenderableWidget(
                Button.builder(Component.literal("X")) { ProxyManager.deletePreset(name); rebuildWidgets() }
                    .bounds(x + bw - 20, y, 20, 20).build(),
            )
            y += 22
        }

        statusWidget = addRenderableWidget(StringWidget(x - 40, height - 26, bw + 80, 12, Component.empty(), f))
        statusWidget?.message = Component.literal(statusText)
        refreshHeader()
    }

    /** Rebuild the header text from the current active proxy + routing state. */
    private fun refreshHeader() {
        val summary = ProxyManager.activeSummary()
        val header = when {
            summary == null -> "§7Proxy Manager"
            ProxyManager.isRouting() -> "§a● Proxy: §f$summary"
            else -> "§e○ Proxy (disabled): §f$summary"
        }
        headerWidget?.message = Component.literal(header)
    }

    private fun setStatus(s: String) {
        statusText = s
        statusWidget?.message = Component.literal(s)
    }

    private fun editBox(f: Font, x: Int, y: Int, w: Int, name: String, hint: String, value: String): EditBox {
        val box = EditBox(f, x, y, w, 20, Component.literal(name))
        box.setMaxLength(256)
        box.setHint(Component.literal(hint))
        box.value = value
        return addRenderableWidget(box)
    }

    private fun typeLabel(): Component = Component.literal("Type: ${ProxyManager.getType()}")

    private fun cycleType() {
        ProxyManager.cycleType()
        typeButton?.message = typeLabel()
    }

    private fun apply() {
        if (!ProxyManager.applyFields(ipBox?.value ?: "", userBox?.value ?: "", passBox?.value ?: "")) {
            setStatus("§cInvalid proxy — use host:port.")
            return
        }
        ModuleManager.saveConfigurations()
        setStatus("§aProxy set §7(applies on next connect).")
        refreshHeader()
    }

    private fun test() {
        setStatus("§7Testing…")
        ProxyManager.testFields(ipBox?.value ?: "", userBox?.value ?: "", passBox?.value ?: "")
    }

    private fun save() {
        ProxyManager.applyFields(ipBox?.value ?: "", userBox?.value ?: "", passBox?.value ?: "")
        ProxyManager.savePreset(nameBox?.value ?: "")
        nameBox?.value = ""
        rebuildWidgets()
    }

    override fun onClose() {
        ProxyManager.statusSink = null
        ModuleManager.saveConfigurations()
        Fish5.mc.gui.setScreen(parent)
    }
}
