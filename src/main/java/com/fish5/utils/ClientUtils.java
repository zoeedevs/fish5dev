// Ported from aether (https://github.com/mizly/aether), GPL-3.0-or-later.
// Modified for fish5; see com/fish5/PORTED.md.
package com.fish5.utils;

import com.mojang.blaze3d.platform.InputConstants;
import com.fish5.config.MacroConfig;
import com.fish5.config.ConfigHelpers;
import com.fish5.mixin.AbstractContainerScreenAccessor;
import com.fish5.mixin.KeyMappingAccessor;
import com.fish5.macro.MacroState;
import com.fish5.failsafe.FailsafeManager;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ClientUtils {
    private static final long GUI_CLOSE_STABILIZATION_MS = 250L;
    private static final long GUI_CLOSE_POLL_MS = 25L;
    private static final Object SIDEBAR_CACHE_LOCK = new Object();
    private static final Pattern STRIP_SCOREBOARD_FORMATTING = Pattern.compile("\u00A7.");
    private static final Pattern STRIP_MINECRAFT_FORMATTING = Pattern.compile("(?i)\u00A7[0-9A-FK-ORZ]");
    private static final DateTimeFormatter DEBUG_LOG_FILE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH_mm'.txt'");
    private static final DateTimeFormatter DEBUG_LOG_LINE_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Object DEBUG_LOG_LOCK = new Object();
    private static final Path DEBUG_LOG_FILE = createDebugLogFile();
    private static int sidebarCacheTick = Integer.MIN_VALUE;
    private static int sidebarCacheConnectionId = 0;
    private static List<String> sidebarCacheLines = List.of();
    private static final Object COMMAND_QUEUE_LOCK = new Object();
    // Commands and GUI closes share one thread: both are infrequent and never need to run in
    // parallel. Input stays separate so a blocking close can't delay a key release.
    private static final ScheduledExecutorService COMMAND_EXECUTOR = DaemonExecutors.singleScheduled("fish5-command-dispatch");
    private static final ScheduledExecutorService INPUT_EXECUTOR = DaemonExecutors.singleScheduled("fish5-input-dispatch");
    private static long nextCommandTime = 0;
    private static final long COMMAND_COOLDOWN_MS = 250;
    private static final long INPUT_CLICK_HOLD_MIN_MS = 80L;
    private static final long INPUT_CLICK_HOLD_JITTER_MS = 60L;

    private static long randomizedClickHoldMs() {
        return INPUT_CLICK_HOLD_MIN_MS + ThreadLocalRandom.current().nextLong(INPUT_CLICK_HOLD_JITTER_MS + 1);
    }
    // use (right) clicks are held shorter; performUseClickNow also queues a vanilla click, so a sub-tick hold still lands
    private static final long USE_CLICK_HOLD_MIN_MS = 20L;
    private static final long USE_CLICK_HOLD_MAX_MS = 80L;

    private static long randomizedUseHoldMs() {
        return ThreadLocalRandom.current().nextLong(USE_CLICK_HOLD_MIN_MS, USE_CLICK_HOLD_MAX_MS + 1);
    }
    private static final String USER_MESSAGE_PREFIX = "\u00A75\u00A7l[Fish 5] \u00A77";
    private static final AtomicLong USE_CLICK_SEQUENCE = new AtomicLong();
    private static final AtomicLong ATTACK_CLICK_SEQUENCE = new AtomicLong();

    public static void sendMessage(String message) {
        sendMessage(message, false);
    }

    public static void sendMessage(String message, boolean overlay) {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return;
        }
        String normalized = normalizeUserMessage(message);
        if (!overlay) {
            com.fish5.automation.Msg.chat("§7" + normalized);
            return;
        }
        client.execute(() -> {
            if (client.player != null) {
                client.player.sendOverlayMessage(Component.literal(USER_MESSAGE_PREFIX + normalized));
            }
        });
    }

    public static void sendDebugMessage(String message) {
        String normalized = normalizeDebugMessage(message);
        writeDebugLine(normalized);
        com.fish5.automation.Msg.dbg(normalized);
    }

    public static String formatElapsedMs(long startTimeMs, long nowMs) {
        if (startTimeMs <= 0L) {
            return "0ms";
        }
        return Math.max(0L, nowMs - startTimeMs) + "ms";
    }

    private static String normalizeUserMessage(String message) {
        if (message == null) {
            return "";
        }

        String normalized = message
                .replaceFirst("^(?i)(?:\u00A7[0-9A-FK-OR])*\\[fish5\\]\\s*", "")
                .replaceFirst("^(?i)(?:\u00A7[0-9A-FK-OR])*fish5\\s*>>\\s*(?:\u00A7[0-9A-FK-OR])*", "")
                .trim();

        return normalized;
    }

    private static String normalizeDebugMessage(String message) {
        if (message == null) {
            return "";
        }

        String withoutPrefixes = message
                .replaceFirst("^(?i)(?:\u00A7[0-9A-FK-OR])*\\[Debug\\]\\s*", "")
                .replaceFirst("^(?i)(?:\u00A7[0-9A-FK-OR])*\\[fish5\\]\\s*", "")
                .replaceFirst("^(?i)(?:\u00A7[0-9A-FK-OR])*fish5\\s*>>\\s*(?:\u00A7[0-9A-FK-OR])*", "")
                .trim();
        return STRIP_MINECRAFT_FORMATTING.matcher(withoutPrefixes).replaceAll("").trim();
    }

    private static Path createDebugLogFile() {
        return FabricLoader.getInstance()
                .getConfigDir()
                .resolve("fish5")
                .resolve("logs")
                .resolve(LocalDateTime.now().format(DEBUG_LOG_FILE_FORMAT));
    }

    private static void writeDebugLine(String message) {
        synchronized (DEBUG_LOG_LOCK) {
            try {
                Path parent = DEBUG_LOG_FILE.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(
                        DEBUG_LOG_FILE,
                        "[" + LocalTime.now().format(DEBUG_LOG_LINE_TIME_FORMAT) + "] " + message + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (IOException e) {
                System.err.println("[fish5] Failed to write debug log: " + e.getMessage());
            }
        }
    }

    public static void sendCommand(String cmd) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || cmd == null || cmd.isBlank()) {
            return;
        }

        long delayMs;
        synchronized (COMMAND_QUEUE_LOCK) {
            long now = System.currentTimeMillis();
            long scheduledTime = Math.max(now, nextCommandTime);
            nextCommandTime = scheduledTime + COMMAND_COOLDOWN_MS;
            delayMs = Math.max(0L, scheduledTime - now);
        }

        COMMAND_EXECUTOR.schedule(() -> client.execute(() -> {
            if (client.player == null || client.getConnection() == null) {
                return;
            }

            if (cmd.startsWith("/")) {
                client.getConnection().sendCommand(cmd.substring(1));
            } else {
                client.getConnection().sendChat(cmd);
            }
        }), delayMs, TimeUnit.MILLISECONDS);
    }

    public static void disconnectWithScreen(Screen screen, Component reason) {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return;
        }

        client.execute(() -> {
            ClientPacketListener connection = client.getConnection();
            if (connection != null) {
                connection.getConnection().disconnect(reason);
            }

            client.disconnect(screen, false);
        });
    }

    public static void forceReleaseKeys() {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return;
        }

        if (client.options != null) {
            releaseKeyMapping(client.options.keyUp);
            releaseKeyMapping(client.options.keyDown);
            releaseKeyMapping(client.options.keyLeft);
            releaseKeyMapping(client.options.keyRight);
            releaseKeyMapping(client.options.keyJump);
            releaseKeyMapping(client.options.keyShift);
            releaseKeyMapping(client.options.keySprint);
            releaseKeyMapping(client.options.keyAttack);
            releaseKeyMapping(client.options.keyUse);
            ProgrammaticAttackTracker.setHeld(client.options.keyAttack, false);
            restorePhysicalKeyStates(client);
        }
    }

    public static boolean isInventoryScreenOpen() {
        Minecraft client = Minecraft.getInstance();
        return client != null && client.gui.screen() instanceof AbstractContainerScreen<?>;
    }

    // keeps closing any gui that reappears for the full stabilization window, so callers can rely on it not returning before the next action is safe
    public static void closeGui(Minecraft client) {
        if (client == null) {
            return;
        }

        long deadline = System.currentTimeMillis() + GUI_CLOSE_STABILIZATION_MS;
        boolean interrupted = false;
        Screen screenBeingClosed = null;
        while (true) {
            long remaining = deadline - System.currentTimeMillis();
            Screen currentScreen = client.gui.screen();
            if (currentScreen == null) {
                screenBeingClosed = null;
            } else if (currentScreen != screenBeingClosed) {
                screenBeingClosed = currentScreen;
                Runnable closeAction = () -> {
                    if (client.player != null && client.gui.screen() != null) {
                        client.player.closeContainer();
                    } else if (client.gui.screen() != null) {
                        client.gui.setScreen(null);
                    }
                };

                if (client.isSameThread()) {
                    closeAction.run();
                } else {
                    client.execute(closeAction);
                }
            }

            if (remaining <= 0) {
                break;
            }

            try {
                Thread.sleep(Math.min(GUI_CLOSE_POLL_MS, remaining));
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    public static CompletableFuture<Void> closeGuiAsync(Minecraft client) {
        return CompletableFuture.runAsync(() -> closeGui(client), COMMAND_EXECUTOR);
    }

    public static void forceReleaseMovementKeys() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.options == null) {
            return;
        }

        releaseKeyMapping(client.options.keyUp);
        releaseKeyMapping(client.options.keyDown);
        releaseKeyMapping(client.options.keyLeft);
        releaseKeyMapping(client.options.keyRight);
        releaseKeyMapping(client.options.keyJump);
        releaseKeyMapping(client.options.keyShift);
        releaseKeyMapping(client.options.keySprint);
    }

    public static MacroState.Location getCurrentLocation() {
        Minecraft client = Minecraft.getInstance();
        return getCurrentLocation(client);
    }

    private static MacroState.Location getCurrentLocation(Minecraft client) {
        if (client == null || client.level == null || client.player == null || client.getConnection() == null)
            return MacroState.Location.UNKNOWN;

        if (!client.isSameThread()) {
            CompletableFuture<MacroState.Location> future = new CompletableFuture<>();
            client.execute(() -> {
                future.complete(getCurrentLocation(client));
            });
            try {
                return future.get(1, TimeUnit.SECONDS);
            } catch (Exception e) {
                return MacroState.Location.UNKNOWN;
            }
        }

        Scoreboard scoreboard = client.level.getScoreboard();
        Objective sidebar = scoreboard != null
                ? scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR)
                : null;

        if (sidebar == null)
            return MacroState.Location.LIMBO;

        boolean hasLobbyItems = false;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (stack != null && !stack.isEmpty()) {
                String itemName = TablistUtils.stripColors(stack.getHoverName().getString()).trim();
                if (itemName.contains("Game Menu") || itemName.contains("My Profile")) {
                    hasLobbyItems = true;
                    break;
                }
            }
        }

        return SkyblockLocation.resolve(sidebar.getDisplayName().getString(), hasLobbyItems,
                TablistUtils.findLine(client, "Area:"));
    }

    public static List<String> getSidebarLines() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.level == null || client.player == null) {
            return List.of();
        }

        if (!client.isSameThread()) {
            CompletableFuture<List<String>> future = new CompletableFuture<>();
            client.execute(() -> future.complete(getSidebarLines()));
            try {
                return future.get(1, TimeUnit.SECONDS);
            } catch (Exception e) {
                return List.of();
            }
        }

        Scoreboard scoreboard = client.level.getScoreboard();
        Objective sidebar = scoreboard != null ? scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) : null;
        if (sidebar == null || client.getConnection() == null) {
            return List.of();
        }

        int tick = client.player.tickCount;
        int connectionId = System.identityHashCode(client.getConnection());
        synchronized (SIDEBAR_CACHE_LOCK) {
            if (tick == sidebarCacheTick && connectionId == sidebarCacheConnectionId) {
                return sidebarCacheLines;
            }
        }

        Collection<PlayerScoreEntry> scores = scoreboard.listPlayerScores(sidebar);
        List<String> lines = new ArrayList<>(scores.size());
        for (PlayerScoreEntry entry : scores) {
            String entryName = entry.owner();
            PlayerTeam team = scoreboard.getPlayersTeam(entryName);
            String fullText = entryName;
            if (team != null) {
                fullText = team.getPlayerPrefix().getString() + entryName + team.getPlayerSuffix().getString();
            }
            lines.add(STRIP_SCOREBOARD_FORMATTING.matcher(fullText).replaceAll("").trim());
        }

        List<String> cachedLines = List.copyOf(lines);
        synchronized (SIDEBAR_CACHE_LOCK) {
            sidebarCacheTick = tick;
            sidebarCacheConnectionId = connectionId;
            sidebarCacheLines = cachedLines;
            return sidebarCacheLines;
        }
    }

    public static boolean hasLineOfSight(Player player, Vec3 target) {
        if (player.level() == null)
            return false;
        Vec3 eyePos = player.getEyePosition();
        ClipContext context = new ClipContext(
                eyePos, target,
                ClipContext.Block.VISUAL,
                ClipContext.Fluid.NONE,
                player);
        BlockHitResult result = player.level().clip(context);
        return result.getType() == HitResult.Type.MISS;
    }

    public static void lookAt(Player player, Vec3 target) {
        RotationUtils.Rotation rot = RotationUtils
                .calculateLookAt(player.getEyePosition(), target);
        RotationUtils.Rotation adjustedRot = RotationUtils.getAdjustedEnd(
                new RotationUtils.Rotation(player.getYRot(), player.getXRot()),
                rot);
        player.setYRot(adjustedRot.yaw);
        player.setXRot(adjustedRot.pitch);
        FailsafeManager.expectRotation(adjustedRot.yaw, adjustedRot.pitch);
    }

    public static void sleepRandom(int min, int max) throws InterruptedException {
        long sleepTime = min + (long) (Math.random() * (max - min + 1));
        Thread.sleep(sleepTime);
    }

    public static boolean waitForYChange(double startY, long timeoutMs) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null)
            return false;

        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            double currentY = client.player.getY();
            if (Math.abs(currentY - startY) > 0.1) {
                sendDebugMessage("AOTV Y-change detected: " + String.format("%.2f", startY) + " -> " + String.format("%.2f", currentY) + " (+" + (System.currentTimeMillis() - startTime) + "ms)");
                return true;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException ignored) {
                break;
            }
        }
        sendDebugMessage("AOTV Y-change timeout after " + timeoutMs + "ms. startY=" + String.format("%.2f", startY) + " currentY=" + String.format("%.2f", client.player.getY()));
        return false;
    }

    public static int findAspectOfTheVoidSlot() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null)
            return -1;

        for (int i = 0; i < 36; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (stack != null && !stack.isEmpty()) {
                String itemName = stack.getHoverName().getString().replaceAll("\u00A7[0-9a-fk-or]", "").trim();
                String lowercaseName = itemName.toLowerCase();
                if (lowercaseName.contains("aspect of the void") || lowercaseName.contains("aspect of the end")) {
                    return i;
                }
            }
        }
        return -1;
    }

    public static void performShiftRightClick() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.options == null)
            return;

        long clickId = USE_CLICK_SEQUENCE.incrementAndGet();
        long holdMs = randomizedUseHoldMs();

        client.execute(() -> {
            setKeyMappingState(client.options.keyShift, true);
            // Press use only after shift has been applied on the client thread.
            scheduleClientAction(client, 60, () -> {
                if (USE_CLICK_SEQUENCE.get() != clickId) {
                    return;
                }
                setKeyMappingState(client.options.keyUse, true);
                scheduleClientAction(client, holdMs, () -> {
                    if (USE_CLICK_SEQUENCE.get() == clickId) {
                        setKeyMappingState(client.options.keyUse, false);
                        setKeyMappingState(client.options.keyShift, false);
                    }
                });
            });
        });
    }

    // shift is NOT released - the caller has to call releaseShiftKey() once the gui opens
    public static void performShiftLeftClick() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.options == null)
            return;

        client.execute(() -> {
            if (client.player != null) client.player.setShiftKeyDown(true);
            setKeyMappingState(client.options.keyShift, true);
            // Schedule the attack only after the shift state has been applied on the
            // client thread. This also preserves the delay when called from that thread.
            scheduleClientAction(client, 60, () -> {
                if (client.player == null) return;
                clickKeyMapping(client.options.keyAttack);
            });
        });
    }

    public static void releaseShiftKey() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.options == null) return;
        client.execute(() -> {
            setKeyMappingState(client.options.keyShift, false);
            if (client.player != null) client.player.setShiftKeyDown(false);
        });
    }

    public static void performUseClick() {
        performUseClick(null);
    }

    public static void performUseClick(Runnable beforePress) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.options == null) {
            return;
        }

        pressAndReleaseInput(client, USE_CLICK_SEQUENCE,
                () -> {
                    if (beforePress != null) {
                        beforePress.run();
                    }
                    setKeyMappingState(client.options.keyUse, true);
                },
                () -> setKeyMappingState(client.options.keyUse, false),
                randomizedUseHoldMs());
    }

    // a held-only press waits on minecraft's rightClickDelay and is dropped outright if the last use was under four ticks ago
    public static void performUseClickNow() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.options == null) {
            return;
        }

        pressAndReleaseInput(client, USE_CLICK_SEQUENCE,
                () -> {
                    setKeyMappingState(client.options.keyUse, true);
                    clickKeyMapping(client.options.keyUse);
                },
                () -> setKeyMappingState(client.options.keyUse, false),
                randomizedUseHoldMs());
    }

    // for the stun tap right before a hotbar swap: minecraft consumes the click this tick, so it cannot overlap the lasso throw
    public static void performUseClickInstant() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.options == null) {
            return;
        }
        USE_CLICK_SEQUENCE.incrementAndGet();
        client.execute(() -> {
            setKeyMappingState(client.options.keyUse, true);
            clickKeyMapping(client.options.keyUse);
            setKeyMappingState(client.options.keyUse, false);
        });
    }

    // queues one click too, so the first use fires this tick instead of waiting out rightClickDelay; the caller owns the release
    public static void beginUseHoldNow() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.options == null) {
            return;
        }
        USE_CLICK_SEQUENCE.incrementAndGet();
        client.execute(() -> {
            setKeyMappingState(client.options.keyUse, true);
            clickKeyMapping(client.options.keyUse);
        });
    }

    public static void endUseHold() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.options == null) {
            return;
        }
        USE_CLICK_SEQUENCE.incrementAndGet();
        client.execute(() -> {
            setKeyMappingState(client.options.keyUse, false);
            discardQueuedClicks(client.options.keyUse);
        });
    }

    public static void performAttackClickDirect() {
        Minecraft client = Minecraft.getInstance();
        if (client == null) return;
        client.execute(() -> {
            if (client.player == null) return;
            clickKeyMapping(client.options.keyAttack);
        });
    }

    public static void performAttackClick() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.options == null) {
            return;
        }

        pressAndReleaseInput(client, ATTACK_CLICK_SEQUENCE,
                () -> setKeyMappingState(client.options.keyAttack, true),
                () -> setKeyMappingState(client.options.keyAttack, false),
                randomizedClickHoldMs());
    }

    // routes through the screen's slotClicked rather than calling gameMode.handleInventoryMouseClick directly
    public static void performSlotClick(AbstractContainerScreen<?> screen, int slotIndex, int mouseButton, ContainerInput type) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || screen.getMenu() == null) return;
        List<Slot> slots = screen.getMenu().slots;
        if (slotIndex < 0 || slotIndex >= slots.size()) return;
        Slot slot = slots.get(slotIndex);
        ((AbstractContainerScreenAccessor) screen).fish5$invokeSlotClicked(slot, slot.index, mouseButton, type);
    }

    public static long getGuiClickDelayMs(boolean firstClick) {
        return firstClick
                ? ConfigHelpers.getRandomizedDelay(
                        MacroConfig.GUI_FIRST_CLICK_DELAY_MIN.get(),
                        MacroConfig.GUI_FIRST_CLICK_DELAY_MAX.get())
                : ConfigHelpers.getRandomizedDelay(
                        MacroConfig.GUI_CLICK_DELAY_MIN.get(),
                        MacroConfig.GUI_CLICK_DELAY_MAX.get());
    }

    public static void performHotbarSlotClick(int slot) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.options == null || slot < 0 || slot >= client.options.keyHotbarSlots.length) {
            return;
        }

        if (client.isSameThread()) {
            clickKeyMapping(client.options.keyHotbarSlots[slot]);
            return;
        }

        client.execute(() -> clickKeyMapping(client.options.keyHotbarSlots[slot]));
    }

    // releasing a key does not drop clicks already queued on it, and minecraft only drains that queue with no screen open, so they fire whenever a gui closes
    public static void discardQueuedClicks(KeyMapping mapping) {
        if (mapping == null) {
            return;
        }

        while (mapping.consumeClick()) {
        }
    }

    public static void setKeyMappingState(KeyMapping mapping, boolean down) {
        if (mapping == null) {
            return;
        }

        mapping.setDown(down);
    }

    // vanilla grabMouse() calls KeyMapping.setAll(), which resets bindings to the physical key state and would drop macro-held keys until the next tick
    public static void reapplyProgrammaticKeyStates(Minecraft client) {
        if (client == null || client.options == null) {
            return;
        }

        reapplyProgrammaticKey(client.options.keyLeft);
        reapplyProgrammaticKey(client.options.keyRight);
        reapplyProgrammaticKey(client.options.keyUp);
        reapplyProgrammaticKey(client.options.keyDown);
        reapplyProgrammaticKey(client.options.keyShift);
        reapplyProgrammaticKey(client.options.keySprint);
        reapplyProgrammaticKey(client.options.keyJump);
        if (ProgrammaticAttackTracker.isHeld()) {
            setKeyMappingState(client.options.keyAttack, true);
        }
    }

    private static void reapplyProgrammaticKey(KeyMapping mapping) {
        if (ProgrammaticMovementTracker.isDown(mapping)) {
            setKeyMappingState(mapping, true);
        }
    }

    private static void releaseKeyMapping(KeyMapping mapping) {
        setKeyMappingState(mapping, false);
        ProgrammaticMovementTracker.clear(mapping);
    }

    private static void restorePhysicalKeyStates(Minecraft client) {
        if (client == null) {
            return;
        }

        if (client.isSameThread()) {
            KeyMapping.setAll();
            return;
        }

        client.execute(KeyMapping::setAll);
    }

    public static void clickKeyMapping(KeyMapping mapping) {
        if (mapping == null) {
            return;
        }

        InputConstants.Key key = getBoundKey(mapping);
        if (key != null && key != InputConstants.UNKNOWN) {
            KeyMapping.click(key);
        }
    }

    private static void pressAndReleaseInput(
            Minecraft client,
            AtomicLong sequence,
            Runnable pressAction,
            Runnable releaseAction,
            long holdMs
    ) {
        long clickId = sequence.incrementAndGet();

        if (client.isSameThread()) {
            pressAction.run();
            scheduleClientAction(client, holdMs, () -> {
                if (sequence.get() == clickId) {
                    releaseAction.run();
                }
            });
            return;
        }

        client.execute(pressAction);

        try {
            Thread.sleep(holdMs);
        } catch (InterruptedException ignored) {
        }

        client.execute(() -> {
            if (sequence.get() == clickId) {
                releaseAction.run();
            }
        });
    }

    private static void scheduleClientAction(Minecraft client, long delayMs, Runnable action) {
        INPUT_EXECUTOR.schedule(() -> client.execute(action), delayMs, TimeUnit.MILLISECONDS);
    }

    public static void scheduleClientTask(Minecraft client, long delayMs, Runnable action) {
        if (client == null || action == null) {
            return;
        }
        scheduleClientAction(client, delayMs, action);
    }

    private static InputConstants.Key getBoundKey(KeyMapping mapping) {
        return ((KeyMappingAccessor) mapping).getKey();
    }

    public static void waitForRotationToComplete(float targetPitch, int rotationTime) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null)
            return;

        long startTime = System.currentTimeMillis();
        long timeout = 5000; // 5 second timeout

        while (System.currentTimeMillis() - startTime < timeout) {
            float currentPitch = client.player.getXRot();
            float pitchDiff = Math.abs(currentPitch - targetPitch);

            if (pitchDiff < 1.0f) {
                break; // Rotation complete
            }

            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
            }
        }
    }
}
