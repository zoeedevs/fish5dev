package com.fish5.account;

import java.util.UUID;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/** Result of a successful login: who we're logging in as, and the secret to save for next time. */
@Environment(EnvType.CLIENT)
record Credentials(String name, UUID uuid, String accessToken, String secret) {
}
