package com.fish5.session;

import com.fish5.automation.Msg;
import com.fish5.config.MacroConfig;
import com.fish5.failsafe.DesktopNotificationManager;
import com.fish5.failsafe.FailsafeAction;
import com.fish5.failsafe.FailsafeSoundManager;
import com.fish5.macro.MacroStateManager;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

// reacts to the server ending our play session: a ban locks out every automatic reconnect, any other drop just stops the macro
// nothing here is reported anywhere; it only changes what fish5 does locally
public final class BanGuard {
    private static volatile boolean locked = false;
    private static volatile HypixelBanDetector.DetectedBan lastBan;

    private BanGuard() {}

    public static boolean isLocked() {
        return locked;
    }

    public static HypixelBanDetector.DetectedBan getLastBan() {
        return lastBan;
    }

    // called from ClientCommonPacketListenerMixin for an established play connection
    public static void onPlayDisconnect(Component reason) {
        Minecraft client = Minecraft.getInstance();
        if (RestScheduler.consumeIntentionalDisconnect()) {
            return;
        }

        String text = reason == null ? "" : reason.getString();
        HypixelBanDetector.DetectedBan ban = HypixelBanDetector.detect(text);
        client.execute(() -> {
            if (ban != null) {
                onBan(client, ban);
            } else if (MacroStateManager.isMacroRunning()) {
                MacroStateManager.stopMacro(client, "Disconnected: " + text.replace('\n', ' '), false);
            }
        });
    }

    // a successful join means the ban is over (or the user logged in elsewhere on purpose), so automation may run again
    public static void tick(Minecraft client) {
        if (locked && client.player != null && client.level != null) {
            locked = false;
            Msg.LOG.info("[BanGuard] rejoined a server, automatic reconnects unlocked");
        }
    }

    private static void onBan(Minecraft client, HypixelBanDetector.DetectedBan ban) {
        locked = true;
        lastBan = ban;
        RestScheduler.cancel();
        MacroStateManager.stopMacro(client, "Ban screen detected", false);

        String kind = ban.temporary()
                ? "temporary ban" + (ban.duration().isEmpty() ? "" : " (" + ban.duration() + ")")
                : "permanent ban";
        String details = "Hypixel " + kind + (ban.reason().isEmpty() ? "" : ": " + ban.reason())
                + ". Automatic reconnects are off until you rejoin manually.";
        Msg.LOG.warn("[BanGuard] {}", details);
        FailsafeSoundManager.playConfiguredSound(FailsafeAction.STOP);
        if (MacroConfig.FAILSAFE_DESKTOP_NOTIFICATION_ENABLED.get()) {
            DesktopNotificationManager.notify("fish5: ban detected", details, true);
        }
    }
}
