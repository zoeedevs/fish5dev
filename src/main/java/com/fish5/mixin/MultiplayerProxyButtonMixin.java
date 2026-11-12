package com.fish5.mixin;

import com.fish5.clickgui.AccountScreen;
import com.fish5.clickgui.ProxyScreen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds "Accounts" and "Proxy" buttons, side by side in the top-left corner, to the Multiplayer
 * server-select screen - opening {@link AccountScreen} / {@link ProxyScreen} respectively. Same
 * pair and layout as the title screen (see TitleAccountsButtonMixin), so both are reachable right
 * before picking a server too. Extends {@link Screen} so the mixin can reach the inherited
 * protected {@code addRenderableWidget}.
 */
@Mixin(JoinMultiplayerScreen.class)
public abstract class MultiplayerProxyButtonMixin extends Screen {

    private MultiplayerProxyButtonMixin(Component title) {
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
