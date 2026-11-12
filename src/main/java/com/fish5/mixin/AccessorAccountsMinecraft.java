package com.fish5.mixin;

import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.minecraft.UserApiService.UserProperties;
import com.mojang.authlib.yggdrasil.ProfileResult;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.screens.social.PlayerSocialManager;
import net.minecraft.client.gui.screens.social.RemoteFriendListUpdateHandler;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.client.multiplayer.chat.report.ReportingContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the normally-final login state on {@link Minecraft} so {@link com.fish5.account.GameSession}
 * can swap the active account without restarting the game - same trick the vanilla launcher does
 * once at startup, done again on demand here.
 */
@Environment(EnvType.CLIENT)
@Mixin(Minecraft.class)
public interface AccessorAccountsMinecraft {
    @Mutable
    @Accessor("user")
    void fish5$setUser(User user);

    @Mutable
    @Accessor("userApiService")
    void fish5$setUserApiService(UserApiService service);

    @Mutable
    @Accessor("userPropertiesFuture")
    void fish5$setUserPropertiesFuture(CompletableFuture<UserProperties> future);

    @Mutable
    @Accessor("profileFuture")
    void fish5$setProfileFuture(CompletableFuture<ProfileResult> future);

    @Mutable
    @Accessor("playerSocialManager")
    void fish5$setPlayerSocialManager(PlayerSocialManager manager);

    @Accessor("remoteFriendListUpdateHandler")
    RemoteFriendListUpdateHandler fish5$getRemoteFriendListUpdateHandler();

    @Mutable
    @Accessor("remoteFriendListUpdateHandler")
    void fish5$setRemoteFriendListUpdateHandler(RemoteFriendListUpdateHandler handler);

    @Mutable
    @Accessor("profileKeyPairManager")
    void fish5$setProfileKeyPairManager(ProfileKeyPairManager manager);

    @Accessor("reportingContext")
    void fish5$setReportingContext(ReportingContext context);
}
