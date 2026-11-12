package com.fish5.account;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Result of a background validity check against the account's provider (Microsoft for
 * {@link AccountType#MICROSOFT}/{@link AccountType#REFRESH_TOKEN}, the profile endpoint for
 * {@link AccountType#SESSION}). Not persisted - recomputed each launch by {@link Accounts#checkAll()}.
 */
@Environment(EnvType.CLIENT)
public enum AccountStatus {
    UNKNOWN,
    CHECKING,
    VALID,
    EXPIRED,
    ERROR
}
