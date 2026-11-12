package com.fish5.account;

import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.minecraft.UserApiService.UserProperties;
import com.mojang.authlib.yggdrasil.FriendsService;
import com.mojang.authlib.yggdrasil.ProfileResult;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import com.fish5.mixin.AccessorAccountsMinecraft;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.screens.social.PlayerSocialManager;
import net.minecraft.client.gui.screens.social.RemoteFriendListUpdateHandler;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.client.multiplayer.chat.report.ReportEnvironment;
import net.minecraft.client.multiplayer.chat.report.ReportingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds and swaps in the same login state {@link Minecraft}'s constructor sets up once at
 * startup - user/session, friends list, profile key pair, abuse reporting - so switching accounts
 * works without restarting the game. {@link #prepare} does the (slow, blocking) network half off
 * the worker thread; {@link #apply} does the (fast) field swap on the client thread.
 */
@Environment(EnvType.CLIENT)
final class GameSession {
    private static final Logger LOGGER = LoggerFactory.getLogger("fish5-account");
    private YggdrasilAuthenticationService mojangAuth;

    GameSession() {
    }

    Prepared prepare(AccountType type, Credentials credentials) throws InterruptedException {
        Minecraft mc = Minecraft.getInstance();
        boolean offline = type == AccountType.CRACKED;
        User user = new User(credentials.name(), credentials.uuid(), credentials.accessToken(), Optional.empty(), Optional.empty());
        YggdrasilAuthenticationService auth = this.mojangAuth(mc);
        UserApiService api = offline ? UserApiService.OFFLINE : auth.createUserApiService(credentials.accessToken());

        UserProperties properties;
        try {
            properties = api.fetchProperties();
        } catch (AuthenticationException e) {
            LOGGER.warn("[fish5] Couldn't fetch user properties for {}", credentials.name(), e);
            properties = UserApiService.OFFLINE_PROPERTIES;
        }

        ProfileResult profile = offline ? null : mc.services().sessionService().fetchProfile(credentials.uuid(), true);
        // Vanilla's own Minecraft constructor creates the friends service unconditionally (even for
        // an unauthenticated session), so the friends list widgets have something to call into.
        FriendsService friends = auth.createFriendsService(credentials.accessToken());
        return new Prepared(user, api, properties, profile, offline, friends);
    }

    void apply(Prepared prepared) {
        Minecraft mc = Minecraft.getInstance();
        AccessorAccountsMinecraft game = (AccessorAccountsMinecraft) mc;
        game.fish5$setUser(prepared.user());
        game.fish5$setUserApiService(prepared.api());
        game.fish5$setUserPropertiesFuture(CompletableFuture.completedFuture(prepared.properties()));
        game.fish5$setProfileFuture(CompletableFuture.completedFuture(prepared.profile()));

        // Mirrors Minecraft's own constructor: a RemoteFriendListUpdateHandler backs the friends
        // list poller and must be rebuilt alongside the PlayerSocialManager it feeds. Close the old
        // one first so its background polling thread doesn't keep running for the previous account.
        RemoteFriendListUpdateHandler oldHandler = game.fish5$getRemoteFriendListUpdateHandler();
        if (oldHandler != null) {
            oldHandler.close();
        }

        RemoteFriendListUpdateHandler handler = new RemoteFriendListUpdateHandler(prepared.friendsService(), mc);
        PlayerSocialManager social = new PlayerSocialManager(mc, prepared.api(), prepared.friendsService(), handler);
        game.fish5$setRemoteFriendListUpdateHandler(handler);
        game.fish5$setPlayerSocialManager(social);
        if (social.isFriendListEnabled()) {
            handler.start();
        }

        game.fish5$setProfileKeyPairManager(
            prepared.offline()
                ? ProfileKeyPairManager.EMPTY_KEY_MANAGER
                : ProfileKeyPairManager.create(prepared.api(), prepared.user(), mc.gameDirectory.toPath())
        );
        game.fish5$setReportingContext(ReportingContext.create(ReportEnvironment.local(), prepared.api()));
    }

    private YggdrasilAuthenticationService mojangAuth(Minecraft mc) {
        if (this.mojangAuth == null) {
            this.mojangAuth = new YggdrasilAuthenticationService(mc.getProxy());
        }

        return this.mojangAuth;
    }

    @Environment(EnvType.CLIENT)
    record Prepared(User user, UserApiService api, UserProperties properties, ProfileResult profile, boolean offline, FriendsService friendsService) {
    }
}
