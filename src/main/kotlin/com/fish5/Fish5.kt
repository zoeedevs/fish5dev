package com.fish5

import com.mojang.blaze3d.platform.InputConstants
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.fish5.clickgui.ClickGUI
import com.fish5.automation.AutoWorker
import com.fish5.automation.ChatWaiter
import com.fish5.automation.Msg
import com.fish5.automation.PurseTracker
import com.fish5.account.Accounts
import com.fish5.features.ModuleKeybinds
import com.fish5.features.impl.misc.UngrabMouse
import com.fish5.features.impl.render.FreecamModule
import com.fish5.features.impl.render.FreelookModule
import com.fish5.features.ModuleManager
import com.fish5.features.impl.failsafe.FailsafeSettings
import com.fish5.features.impl.fishing.Fishing
import com.fish5.features.impl.fishing.Party
import com.fish5.features.impl.fishing.PartySeaCreatures
import com.fish5.features.impl.fishing.SeaCreatureTracker
import com.fish5.features.impl.fishing.WormFishing
import com.fish5.features.impl.skyblock.WormFinder
import com.fish5.features.impl.skyblock.WormfishProfitTracker
import com.fish5.hud.HudManager
import com.fish5.notification.NotificationManager
import com.fish5.sb.SkyblockData
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.PauseScreen
import org.lwjgl.glfw.GLFW

/**
 * Entry point for Fish 5 — the Crystal Hollows worm-fishing toolkit (spot finder, wormfish profit
 * tracker, auto forge) with its click GUI. Open the GUI with `/fish5` / `/f5`.
 */
object Fish5 : ClientModInitializer {

    const val MOD_ID = "fish5"
    const val MOD_NAME = "Fish 5"

    @JvmStatic
    val mc: Minecraft get() = Minecraft.getInstance()

    /** config/fish5/ — home for the click-GUI module config. */
    val configDir: java.io.File by lazy {
        java.io.File(mc.gameDirectory, "config/fish5").apply { runCatching { mkdirs() } }
    }

    /** Guards the one-shot [Accounts.autoLogin] call (fires on the first client tick). */
    private var accountsAutoLoginDone = false

    override fun onInitializeClient() {
        // Touch ModuleManager so all modules register and their saved config loads at startup.
        ModuleManager

        // Rotation / pathfinding / failsafe core (ported from aether): tick, per-frame rotation, HUD flash.
        com.fish5.macro.MacroRuntime.init()

        // Wire the Wormfish Session/Lifetime/Forge panels into the gameplay HUD (once).
        WormfishProfitTracker.initHud()

        // Register the draggable overlays with the HUD editor.
        PartySeaCreatures.initHud()
        HudManager.register(PartySeaCreatures)
        com.fish5.hud.MacroHud.initHud()
        HudManager.register(com.fish5.hud.MacroHud)

        // Account switcher: start background validity checks for saved accounts; the re-login itself
        // happens on the first tick below.
        Accounts.get().checkAll()

        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { client ->
            if (!accountsAutoLoginDone) {
                accountsAutoLoginDone = true
                // Snapshot the launcher's own login first so the account screen can reset back to it.
                Accounts.get().captureLaunchIdentity()
                Accounts.get().autoLogin()
            }

            PurseTracker.tick(client)

            // Failsafes tab -> MacroConfig (throttled inside).
            FailsafeSettings.sync()

            // GUI keybind + every module's click-GUI toggle key.
            ModuleKeybinds.tick()

            // Freecam / Freelook own keybinds + camera tick; Ungrab Mouse follows them and the macro state.
            FreecamModule.tick()
            FreelookModule.tick()
            UngrabMouse.tick()

            // Our own sea creatures (count + despawn times); before the macro so it reads this tick's count.
            SeaCreatureTracker.tick()
            // measure how far our bobber slides on the fluid vs vanilla (no-op while no bobber is out)
            com.fish5.features.impl.fishing.BobberSlide.tick()
            Party.tick()
            PartySeaCreatures.tick()

            // Worm Fishing cast/reel loop (no-op unless the macro is running).
            WormFishing.tick()
            // NPC auto sell: parks the macro at a safe point, sells through /boostercookie, hands back
            com.fish5.features.impl.misc.AutoSell.tick()
            // a user-paused macro: timer, zone, Esc Esc
            com.fish5.features.MacroPersist.tick()
            // Worm Helper auto mode: forge / claim / list between reels
            com.fish5.features.impl.skyblock.WormAuto.tick()

            // Crystal Hollows spot-finder scan/highlight loop (no-op unless a scan is active).
            WormFinder.tick()
            // /f5 wormhelper find test debug: K records the looked-at block (no-op unless on)
            com.fish5.features.impl.skyblock.BuildRecorder.tick()

            // Live wormfish coins/hr + forge timers (stats no-op unless Worm Helper is enabled).
            WormfishProfitTracker.tick()

            // While auto-forge runs, pressing X stops it immediately. After a short grace, any other
            // user input (move / click / jump / ESC-pause / open chat) also aborts it.
            if (AutoWorker.isBusy()) {
                if (InputConstants.isKeyDown(client.window, GLFW.GLFW_KEY_X)) {
                    AutoWorker.requestAbort()
                } else if (System.currentTimeMillis() - AutoWorker.jobStartedAt > 1500) {
                    val o = client.options
                    val screen = client.gui.screen()
                    // in Freecam the movement keys fly the camera, not the player: flying around isn't
                    // a cancel, or every menu job dies mid-menu with nothing clicked
                    val moving = !com.fish5.features.impl.render.FreeCam.isActive() &&
                        (o.keyUp.isDown || o.keyDown.isDown || o.keyLeft.isDown || o.keyRight.isDown || o.keyJump.isDown)
                    val userInput = moving || o.keyAttack.isDown || o.keyUse.isDown ||
                        screen is PauseScreen || screen is ChatScreen
                    if (userInput) AutoWorker.requestAbort()
                }
            }
        })

        // Feed every game chat line to the automation's chat waiters (stripped + lowercased).
        ClientReceiveMessageEvents.GAME.register(ClientReceiveMessageEvents.Game { message, overlay ->
            if (!overlay) {
                SeaCreatureTracker.onChat(message.string)
                Party.onChat(message.string)
                com.fish5.features.impl.misc.AutoSell.onChat(message.string)
            }
            ChatWaiter.offer(SkyblockData.stripColors(message.string).lowercase())
        })

        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            // /wormhelper ... — same tree as /f5 wormhelper ...
            dispatcher.register(wormHelperCommand())

            for (alias in listOf(MOD_ID, "f5")) {
                dispatcher.register(
                    ClientCommands.literal(alias)
                        .then(
                            ClientCommands.literal("reload").executes {
                                ModuleManager.loadConfigurations()
                                NotificationManager.notify("§b[$MOD_NAME] §aConfig reloaded from disk.")
                                1
                            },
                        )
                        .then(wormHelperCommand())
                        // TEMP TEST: pond pick + cast aim (remove with Fishing.testAim)
                        .then(ClientCommands.literal("aim").executes { mc.execute { Fishing.testAim() }; 1 })
                        // our tracked sea creatures and their despawn timers
                        .then(
                            ClientCommands.literal("sc").executes { mc.execute { SeaCreatureTracker.report() }; 1 }
                                // everything the client knows about the sea creature you look at (owner research)
                                .then(ClientCommands.literal("dump").executes { mc.execute { com.fish5.features.impl.fishing.EntityDump.run() }; 1 }),
                        )
                        // end a paused (or running) macro for good
                        .then(ClientCommands.literal("end").executes { mc.execute { com.fish5.features.MacroPersist.endCommand() }; 1 })
                        // sell the Auto Sell list right now
                        .then(ClientCommands.literal("sell").executes { mc.execute { com.fish5.features.impl.misc.AutoSell.sellNow() }; 1 })
                        // run one automation by hand to test it (macro must be stopped)
                        .then(
                            ClientCommands.literal("test")
                                .then(ClientCommands.literal("auction").executes { mc.execute { com.fish5.features.impl.skyblock.ChamberSeller.testCommand() }; 1 })
                                .then(ClientCommands.literal("forge").executes { mc.execute { com.fish5.features.impl.skyblock.WormForge.testCommand() }; 1 }),
                        )
                        .executes {
                            mc.execute { mc.gui.setScreen(ClickGUI) }
                            1
                        },
                )
            }
        }
    }

    /**
     * `wormhelper` command tree (mounted at `/f5 wormhelper` and `/wormhelper`):
     * find <lava|water> [auto] | find stop, calc [lifetime], report, reset, resetlifetime, debug.
     * Forging lives in Worm Helper's auto mode now.
     */
    private fun wormHelperCommand(): LiteralArgumentBuilder<FabricClientCommandSource> =
        ClientCommands.literal("wormhelper")
            .then(
                ClientCommands.literal("find")
                    .then(
                        ClientCommands.literal("lava")
                            .then(ClientCommands.literal("auto").executes { WormFinder.startAutoHop(WormFinder.FluidKind.LAVA); 1 })
                            // dig-out build template scan (see BuildTemplate)
                            .then(
                                ClientCommands.literal("test")
                                    .then(ClientCommands.literal("debug").executes { mc.execute { com.fish5.features.impl.skyblock.BuildRecorder.toggle() }; 1 })
                                    .executes { mc.execute { WormFinder.restartTemplate() }; 1 },
                            )
                            .executes { WormFinder.restart(WormFinder.FluidKind.LAVA); 1 },
                    )
                    // record a hand-built test structure block by block (K) to config/fish5/build-debug.txt
                    .then(
                        ClientCommands.literal("test")
                            .then(ClientCommands.literal("debug").executes { mc.execute { com.fish5.features.impl.skyblock.BuildRecorder.toggle() }; 1 }),
                    )
                    .then(
                        ClientCommands.literal("water")
                            .then(ClientCommands.literal("auto").executes { WormFinder.startAutoHop(WormFinder.FluidKind.WATER); 1 })
                            .executes { WormFinder.restart(WormFinder.FluidKind.WATER); 1 },
                    )
                    .then(ClientCommands.literal("stop").executes { WormFinder.cancelAll(); 1 })
                    .executes {
                        Msg.chat("§6/f5 wormhelper find <lava|water> [auto] | stop §7— (re)start / auto-hop the Crystal Hollows spot finder; stop clears it.")
                        1
                    },
            )
            .then(
                ClientCommands.literal("calc")
                    .then(ClientCommands.literal("lifetime").executes { WormfishProfitTracker.wormCalcLifetime(); 1 })
                    .executes { WormfishProfitTracker.wormCalc(); 1 },
            )
            .then(ClientCommands.literal("report").executes { WormfishProfitTracker.report(); 1 })
            .then(ClientCommands.literal("reset").executes { WormfishProfitTracker.resetSessionCommand(); 1 })
            .then(ClientCommands.literal("resetlifetime").executes { WormfishProfitTracker.resetLifetimeCommand(); 1 })
            .then(ClientCommands.literal("debug").executes { WormfishProfitTracker.debug(); 1 })
            .executes {
                Msg.chat("§6§lWorm Helper §7— §f/f5 wormhelper §7(or §f/wormhelper§7):")
                Msg.chat("§7  find <lava|water> [auto] | find stop §8— fishing spot finder")
                Msg.chat("§7  calc [lifetime] §8— what forging every membrane right now is worth")
                Msg.chat("§7  report | reset | resetlifetime | debug §8— profit tracker (enable the module)")
                1
            }
}
