package com.fish5.account;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.regex.Pattern;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.core.UUIDUtil;
import net.minecraft.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Saved-account switcher: add a cracked name, a pasted session/refresh token, or sign in with
 * Microsoft; switch between them without restarting the game. Backed by {@link GameSession} for
 * the actual field swap and persisted to {@code config/fish5/accounts.json}.
 */
@Environment(EnvType.CLIENT)
public final class Accounts {
    private static final Logger LOGGER = LoggerFactory.getLogger("fish5-account");
    private static final Accounts INSTANCE = new Accounts();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int CONFIG_VERSION = 1;
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    // Threads come from DaemonExecutors so they exit after 30 s idle (low-RAM rule in CLAUDE.md).
    private final ExecutorService worker = com.fish5.utils.DaemonExecutors.single("fish5 Accounts");
    private final ExecutorService checker = com.fish5.utils.DaemonExecutors.single("fish5 Account Check");
    private final GameSession session = new GameSession();
    private final Map<String, AccountStatus> statuses = new ConcurrentHashMap<>();
    private final List<Account> accounts = new ArrayList<>();
    private volatile List<Account> snapshot = List.of();
    private volatile String activeId;
    private volatile boolean busy;
    private volatile String status = "";
    private volatile Thread microsoftSignIn;
    private volatile boolean loaded;
    private boolean readOnly;
    private volatile Credentials launchCredentials;
    private volatile AccountType launchType;

    private Accounts() {
    }

    public static Accounts get() {
        return INSTANCE;
    }

    public List<Account> list() {
        this.ensureLoaded();
        return this.snapshot;
    }

    public Account active() {
        String sessionUuid = Minecraft.getInstance().getUser().getProfileId().toString();
        List<Account> all = this.list();
        String lastSwitched = this.activeId;

        for (Account account : all) {
            if (account.id().equals(lastSwitched) && account.uuid().equals(sessionUuid)) {
                return account;
            }
        }

        for (Account account : all) {
            if (account.uuid().equals(sessionUuid)) {
                return account;
            }
        }

        return null;
    }

    public CompletableFuture<Account> addCracked(String username) {
        String name = username == null ? "" : username.trim();
        return !VALID_NAME.matcher(name).matches()
            ? this.failed("A cracked name is 1-16 letters, digits or underscores")
            : this.signIn(AccountType.CRACKED, null, "Logging in as " + name + "...", () -> offline(name));
    }

    public CompletableFuture<Account> addSession(String token) {
        String accessToken = token == null ? "" : token.trim();
        return accessToken.isEmpty()
            ? this.failed("Paste an access token first")
            : this.signIn(AccountType.SESSION, null, "Checking the session token...", () -> SessionLogin.signIn(accessToken));
    }

    public CompletableFuture<Account> addMicrosoft() {
        return this.signIn(AccountType.MICROSOFT, null, "Opening Microsoft sign-in...", this::microsoftBrowserSignIn);
    }

    public CompletableFuture<Account> addRefreshToken(String token) {
        String refreshToken = token == null ? "" : token.trim();
        return refreshToken.isEmpty()
            ? this.failed("Paste a refresh token first")
            : this.signIn(AccountType.REFRESH_TOKEN, null, "Signing in with refresh token...", () -> MicrosoftLogin.minecraft(MicrosoftLogin.refresh(refreshToken)));
    }

    public void cancelMicrosoft() {
        Thread signIn = this.microsoftSignIn;
        if (signIn != null) {
            signIn.interrupt();
        }
    }

    public CompletableFuture<Void> login(Account account) {
        Account saved = this.find(account);
        if (saved == null) {
            return this.failed("That account is no longer saved");
        }

        String who = saved.name().isEmpty() ? saved.type().label() + " account" : saved.name();
        return this.signIn(saved.type(), saved.id(), "Logging in as " + who + "...", () -> switch (saved.type()) {
            case CRACKED -> offline(saved.name());
            case SESSION -> SessionLogin.signIn(saved.secret());
            case MICROSOFT, REFRESH_TOKEN -> MicrosoftLogin.minecraft(MicrosoftLogin.refresh(saved.secret()));
        }).thenAccept(signedIn -> {
        });
    }

    public synchronized void remove(Account account) {
        int index = this.indexOf(account);
        if (index >= 0) {
            this.accounts.remove(index);
            this.publish();
            this.save();
        }
    }

    public boolean busy() {
        return this.busy;
    }

    public String status() {
        return this.status;
    }

    /** Not persisted; recomputed each launch (or whenever {@link #check} runs) by probing the provider. */
    public AccountStatus statusOf(Account account) {
        return this.statuses.getOrDefault(account.id(), AccountStatus.UNKNOWN);
    }

    /**
     * Kicks off a background validity check for every saved account: a live refresh for Microsoft /
     * Refresh Token accounts (which also rotates and re-saves the refresh token, since Microsoft's are
     * single-use), and a profile lookup for Session accounts to catch an expired pasted token. Doesn't
     * touch the active game session - only updates {@link #statusOf} and, for Microsoft accounts, the
     * saved secret.
     */
    public void checkAll() {
        List<Account> all = this.list();
        String skip = this.activeId;

        for (Account account : all) {
            if (!account.id().equals(skip)) {
                this.check(account);
            }
        }
    }

    /**
     * Logs back into the account that was active last time the game was closed, if any is saved -
     * same path as clicking its row, so it also refreshes/rotates its token. Safe to call once the
     * game is ticking; does nothing if there's no saved "last active" account or it's no longer saved.
     */
    public void autoLogin() {
        List<Account> all = this.list();
        String id = this.activeId;
        if (id != null) {
            for (Account account : all) {
                if (account.id().equals(id)) {
                    this.login(account);
                    return;
                }
            }
        }
    }

    /**
     * Snapshots whatever Minecraft was already logged in as before this mod touched anything -
     * the launcher's own Microsoft/offline login. Call once, right before the first
     * {@link #autoLogin()} (same client tick), so {@link #resetToLaunch()} can always get back to
     * it even after autoLogin - or any manual switch - has moved the live session elsewhere.
     * Idempotent: later calls are a no-op.
     */
    public void captureLaunchIdentity() {
        if (this.launchCredentials != null) {
            return;
        }

        User user = Minecraft.getInstance().getUser();
        String accessToken = user.getAccessToken() == null ? "" : user.getAccessToken();
        this.launchType = accessToken.isEmpty() ? AccountType.CRACKED : AccountType.SESSION;
        this.launchCredentials = new Credentials(user.getName(), user.getProfileId(), accessToken, accessToken);
    }

    /**
     * Switches back to the login captured by {@link #captureLaunchIdentity()} - "undo" for
     * whatever {@link #autoLogin()} (or a manual switch) did this session. A cracked launch is
     * just re-applied; anything with a real access token is re-validated through the same path a
     * saved Session account uses, since the token may have expired since launch.
     */
    public CompletableFuture<Void> resetToLaunch() {
        Credentials captured = this.launchCredentials;
        AccountType type = this.launchType;
        if (captured == null || type == null) {
            return this.failed("No original login was recorded this launch");
        }

        String who = captured.name().isEmpty() ? "your original login" : captured.name();
        return this.signIn(type, null, "Resetting to " + who + "...", () -> switch (type) {
            case CRACKED -> offline(captured.name());
            default -> SessionLogin.signIn(captured.accessToken());
        }).thenAccept(signedIn -> {
        });
    }

    public void check(Account account) {
        if (account.type() == AccountType.CRACKED) {
            return;
        }

        boolean[] alreadyChecking = new boolean[1];
        this.statuses.compute(account.id(), (id, current) -> {
            if (current == AccountStatus.CHECKING) {
                alreadyChecking[0] = true;
                return current;
            }

            return AccountStatus.CHECKING;
        });
        if (!alreadyChecking[0]) {
            this.checker.execute(() -> this.runCheck(account));
        }
    }

    private static Credentials offline(String name) {
        return new Credentials(name, UUIDUtil.createOfflinePlayerUUID(name), "", "");
    }

    private Credentials microsoftBrowserSignIn() throws InterruptedException {
        this.microsoftSignIn = Thread.currentThread();

        try {
            MicrosoftLogin.DeviceCode code = MicrosoftLogin.requestDeviceCode();
            this.status = "Waiting for Microsoft sign-in in your browser... (code "
                + code.userCode()
                + " at "
                + code.verificationUri().replaceFirst("^https?://", "")
                + ")";
            Util.getPlatform().openUri(code.directUri());
            MicrosoftLogin.MsaToken token = MicrosoftLogin.awaitApproval(code);
            this.status = "Signing in to Xbox Live...";
            return MicrosoftLogin.minecraft(token);
        } finally {
            this.microsoftSignIn = null;
        }
    }

    private void runCheck(Account account) {
        AccountStatus result;

        try {
            switch (account.type()) {
                case SESSION -> SessionLogin.signIn(account.secret());
                case MICROSOFT, REFRESH_TOKEN -> this.rotateRefreshToken(account);
                default -> {
                    return;
                }
            }

            result = AccountStatus.VALID;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (AccountException e) {
            result = e.getCause() == null ? AccountStatus.EXPIRED : AccountStatus.ERROR;
            LOGGER.info("[fish5] {} account check: {}", account.type().label(), e.getMessage());
        } catch (RuntimeException e) {
            LOGGER.warn("[fish5] Couldn't check {}", account, e);
            result = AccountStatus.ERROR;
        }

        this.statuses.put(account.id(), result);
    }

    private void rotateRefreshToken(Account account) throws InterruptedException {
        MicrosoftLogin.MsaToken token = MicrosoftLogin.refresh(account.secret());
        Credentials credentials = MicrosoftLogin.minecraft(token);
        this.updateSecret(account, credentials);
    }

    private synchronized void updateSecret(Account account, Credentials credentials) {
        int index = this.indexOf(account);
        if (index >= 0) {
            this.accounts.set(index, this.accounts.get(index).withLogin(credentials.name(), credentials.uuid().toString(), credentials.secret()));
            this.publish();
            this.save();
        }
    }

    private CompletableFuture<Account> signIn(AccountType type, String savedId, String progress, SignIn blocking) {
        synchronized (this) {
            if (this.busy) {
                return CompletableFuture.failedFuture(new AccountException("Another account action is still running"));
            }

            if (inGame()) {
                return this.failed("Leave the world before switching accounts");
            }

            this.busy = true;
        }

        this.ensureLoaded();
        this.status = progress;
        CompletableFuture<Account> result = new CompletableFuture<>();
        this.worker.execute(() -> {
            Thread.interrupted();

            try {
                Credentials credentials = blocking.run();
                GameSession.Prepared prepared = this.session.prepare(type, credentials);
                Minecraft.getInstance().execute(() -> this.finish(type, savedId, credentials, prepared, result));
            } catch (InterruptedException e) {
                this.settleLater(result, new AccountException(type == AccountType.MICROSOFT ? "Microsoft sign-in cancelled" : "Login cancelled"));
            } catch (AccountException e) {
                LOGGER.warn("[fish5] {} login failed: {}", type.label(), e.getMessage());
                this.settleLater(result, e);
            } catch (RuntimeException e) {
                LOGGER.warn("[fish5] {} login failed", type.label(), e);
                this.settleLater(result, new AccountException("Login failed: " + e.getMessage(), e));
            }
        });
        return result;
    }

    private void finish(AccountType type, String savedId, Credentials credentials, GameSession.Prepared prepared, CompletableFuture<Account> result) {
        Account account;
        synchronized (this) {
            account = this.remember(type, savedId, credentials);
        }

        if (inGame()) {
            this.settle(result, null, new AccountException("Joined a world before the switch finished - leave it and log in again"));
            return;
        }

        try {
            this.session.apply(prepared);
        } catch (RuntimeException e) {
            LOGGER.error("[fish5] Switching the session to {} failed", account, e);
            this.settle(result, null, new AccountException("Couldn't switch the game to " + credentials.name() + ": " + e.getMessage(), e));
            return;
        }

        this.setActive(account);
        LOGGER.info("[fish5] Switched session to {}", account);
        this.status = "Logged in as " + credentials.name();
        this.settle(result, account, null);
    }

    private synchronized void setActive(Account account) {
        if (!account.id().equals(this.activeId)) {
            this.activeId = account.id();
            this.save();
        }
    }

    private void settleLater(CompletableFuture<Account> result, AccountException error) {
        Minecraft.getInstance().execute(() -> this.settle(result, null, error));
    }

    private void settle(CompletableFuture<Account> result, Account account, AccountException error) {
        if (error != null) {
            this.status = error.getMessage();
        }

        this.busy = false;
        if (error != null) {
            result.completeExceptionally(error);
        } else {
            result.complete(account);
        }
    }

    private Account remember(AccountType type, String savedId, Credentials credentials) {
        String uuid = credentials.uuid().toString();
        int index = -1;

        for (int i = 0; i < this.accounts.size(); i++) {
            Account existing = this.accounts.get(i);
            boolean same = savedId != null
                ? existing.id().equals(savedId)
                : existing.type() == type && (type == AccountType.CRACKED ? existing.name().equalsIgnoreCase(credentials.name()) : existing.uuid().equals(uuid));
            if (same) {
                index = i;
                break;
            }
        }

        if (index < 0 && savedId != null) {
            return new Account(savedId, type, credentials.name(), uuid, credentials.secret());
        }

        Account account = index >= 0
            ? this.accounts.get(index).withLogin(credentials.name(), uuid, credentials.secret())
            : new Account(UUID.randomUUID().toString(), type, credentials.name(), uuid, credentials.secret());
        if (index >= 0) {
            this.accounts.set(index, account);
        } else {
            this.accounts.add(account);
        }

        this.publish();
        this.save();
        return account;
    }

    private static boolean inGame() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null || mc.getConnection() != null;
    }

    private <T> CompletableFuture<T> failed(String message) {
        this.status = message;
        return CompletableFuture.failedFuture(new AccountException(message));
    }

    private synchronized Account find(Account account) {
        int index = this.indexOf(account);
        return index < 0 ? null : this.accounts.get(index);
    }

    private int indexOf(Account account) {
        this.ensureLoaded();
        if (account == null) {
            return -1;
        }

        for (int i = 0; i < this.accounts.size(); i++) {
            if (this.accounts.get(i).id().equals(account.id())) {
                return i;
            }
        }

        return -1;
    }

    private void publish() {
        this.snapshot = List.copyOf(this.accounts);
    }

    private void ensureLoaded() {
        if (!this.loaded) {
            this.load();
        }
    }

    private synchronized void load() {
        if (this.loaded) {
            return;
        }

        this.loaded = true;
        Path path = configPath();
        if (!Files.isRegularFile(path)) {
            return;
        }

        try {
            try (Reader reader = Files.newBufferedReader(path)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                int version = root.has("version") ? root.get("version").getAsInt() : 0;
                if (version > CONFIG_VERSION) {
                    this.readOnly = true;
                    LOGGER.warn("[fish5] Account list version {} is newer than supported {}; skipping load", version, CONFIG_VERSION);
                    return;
                }

                if (root.has("active") && root.get("active").isJsonPrimitive()) {
                    this.activeId = root.get("active").getAsString();
                }

                if (root.has("accounts") && root.get("accounts").isJsonArray()) {
                    Iterator<JsonElement> it = root.getAsJsonArray("accounts").iterator();
                    while (it.hasNext()) {
                        JsonElement element = it.next();
                        if (element.isJsonObject()) {
                            Account account = read(element.getAsJsonObject());
                            if (account != null) {
                                this.accounts.add(account);
                            }
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[fish5] Failed to load the account list; preserving the file", e);
            this.accounts.clear();
            this.readOnly = true;
            return;
        }

        this.publish();
    }

    private static Account read(JsonObject json) {
        String typeName = AuthHttp.text(json, "type");
        AccountType type = null;

        for (AccountType candidate : AccountType.values()) {
            if (candidate.name().equalsIgnoreCase(typeName)) {
                type = candidate;
            }
        }

        if (type == null) {
            return null;
        }

        String id = AuthHttp.text(json, "id");
        String name = AuthHttp.text(json, "name");
        String uuid = AuthHttp.text(json, "uuid");
        String token = AuthHttp.text(json, "token");
        if (name == null) {
            name = "";
        }

        if (type == AccountType.CRACKED ? VALID_NAME.matcher(name).matches() : token != null && !token.isEmpty()) {
            if (type == AccountType.CRACKED) {
                uuid = UUIDUtil.createOfflinePlayerUUID(name).toString();
            }

            return new Account(id != null && !id.isEmpty() ? id : UUID.randomUUID().toString(), type, name, uuid == null ? "" : uuid, token == null ? "" : token);
        }

        return null;
    }

    private void save() {
        if (this.readOnly) {
            return;
        }

        JsonObject root = new JsonObject();
        root.addProperty("version", CONFIG_VERSION);
        if (this.activeId != null) {
            root.addProperty("active", this.activeId);
        }

        JsonArray list = new JsonArray();

        for (Account account : this.accounts) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", account.id());
            entry.addProperty("type", account.type().name());
            entry.addProperty("name", account.name());
            entry.addProperty("uuid", account.uuid());
            if (!account.secret().isEmpty()) {
                entry.addProperty("token", account.secret());
            }

            list.add(entry);
        }

        root.add("accounts", list);
        Path path = configPath();

        try {
            Files.createDirectories(path.getParent());
            Path tmp = path.resolveSibling(path.getFileName().toString() + ".tmp");

            try (Writer writer = Files.newBufferedWriter(tmp)) {
                GSON.toJson(root, writer);
            }

            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFail) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.warn("[fish5] Failed to save the account list", e);
        }
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("fish5").resolve("accounts.json");
    }

    @Environment(EnvType.CLIENT)
    private interface SignIn {
        Credentials run() throws InterruptedException;
    }
}
