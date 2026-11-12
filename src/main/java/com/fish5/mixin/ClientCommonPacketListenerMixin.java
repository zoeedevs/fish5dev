// Ported from aether (https://github.com/mizly/aether), GPL-3.0-or-later.
// Modified for fish5; see com/fish5/PORTED.md.
package com.fish5.mixin;

import com.fish5.session.BanGuard;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.DisconnectionDetails;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// observe-only: reads the disconnect reason, never answers or alters a packet
@Mixin(ClientCommonPacketListenerImpl.class)
public class ClientCommonPacketListenerMixin {
    // configuration-stage disconnects share this method; the instanceof narrows it to an established play connection
    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void fish5$onDisconnect(DisconnectionDetails details, CallbackInfo ci) {
        if ((Object) this instanceof ClientPacketListener) {
            BanGuard.onPlayDisconnect(details == null ? null : details.reason());
        }
    }
}
