package com.fish5.account;

import com.mojang.util.UndashedUuid;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/** Signs in with a pasted `minecraft/profile` access token (no OAuth dance needed). */
@Environment(EnvType.CLIENT)
final class SessionLogin {
    private SessionLogin() {
    }

    static Credentials signIn(String accessToken) throws InterruptedException {
        AuthHttp.Reply profile = AuthHttp.get("https://api.minecraftservices.com/minecraft/profile", accessToken);
        if (profile.status() == 401) {
            throw new AccountException("This session token is invalid or has expired");
        } else if (profile.status() == 404) {
            throw new AccountException("This token's account doesn't own Minecraft: Java Edition");
        }

        String name = profile.text("name");
        String id = profile.text("id");
        if (profile.ok() && name != null && id != null) {
            return new Credentials(name, UndashedUuid.fromStringLenient(id), accessToken, accessToken);
        }

        throw new AccountException("Couldn't load the profile for this token (HTTP " + profile.status() + ")");
    }
}
