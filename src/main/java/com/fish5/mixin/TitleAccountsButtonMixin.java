package com.fish5.mixin;

import com.fish5.clickgui.AccountScreen;
import com.fish5.clickgui.ProxyScreen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds "Accounts" and "Proxy" buttons, side by side in the top-left corner, to the title screen -
 * opening {@link AccountScreen} / {@link ProxyScreen} respectively. Same pair and layout as the
 * Multiplayer server-select screen (see MultiplayerProxyButtonMixin), so both are reachable
 * before you even open Multiplayer - switching accounts in particular has to happen before you
 * pick a server, and must not be attempted while a world/connection is live - see
 * {@code Accounts#login}.
 */
@Mixin(TitleScreen.class)
public abstract class TitleAccountsButtonMixin extends Screen {

    private TitleAccountsButtonMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void fish5$addAccountAndProxyButtons(CallbackInfo ci) {
        Screen self = (Screen) (Object) this;
        addRenderableWidget(
            Button.builder(Component.literal("Accounts"), b -> Minecraft.getInstance().gui.setScreen(new AccountScreen(self)))
                .bounds(4, 4, 80, 20)
                .build()
        );
        addRenderableWidget(
            Button.builder(Component.literal("Proxy"), b -> Minecraft.getInstance().gui.setScreen(new ProxyScreen(self)))
                .bounds(88, 4, 70, 20)
                .build()
        );
    }
}
