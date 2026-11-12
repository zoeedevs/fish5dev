package com.fish5.account;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/** How an {@link Account}'s credentials were obtained. */
@Environment(EnvType.CLIENT)
public enum AccountType {
    MICROSOFT("Microsoft"),
    SESSION("Session"),
    REFRESH_TOKEN("Refresh Token"),
    CRACKED("Cracked");

    private final String label;

    AccountType(String label) {
        this.label = label;
    }

    public String label() {
        return this.label;
    }
}
