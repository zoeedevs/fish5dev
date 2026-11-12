package com.fish5.sb;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.fish5.automation.Msg;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Minimal Hypixel API client — reads the player's unlocked SkyBlock collection tiers so the maxer can
 * tell whether a craft's collection requirement is met. Only used when crafting is enabled (needs the
 * user's API key). Results are cached per-UUID for a few minutes.
 */
public final class HypixelApi {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private static final long TTL_MS = Duration.ofMinutes(5).toMillis();

    private static volatile String cacheUuid;
    private static volatile Set<String> cacheTiers;
    private static volatile long cacheAt;

    // Separate cache from the collection-tiers one above -- sacks change far more often (every catch),
    // so this needs a much shorter TTL and shouldn't be coupled to the tiers cache's lifetime.
    private static final long SACKS_TTL_MS = Duration.ofSeconds(45).toMillis();
    private static volatile String sacksCacheUuid;
    private static volatile Map<String, Long> sacksCache;
    private static volatile long sacksCacheAt;

    private HypixelApi() {}

    /**
     * Unlocked collection tiers for the player's selected profile, e.g. {@code ["REDSTONE_8", ...]}.
     * Returns null on failure (no key, network error, no profile). Blocking — worker thread only.
     */
    public static Set<String> unlockedCollTiers(String uuidDashless, String apiKey) {
        if (uuidDashless == null || apiKey == null || apiKey.isBlank()) return null;
        Set<String> cached = cacheTiers;
        if (cached != null && uuidDashless.equals(cacheUuid) && System.currentTimeMillis() - cacheAt < TTL_MS) {
            return cached;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(
                            URI.create("https://api.hypixel.net/v2/skyblock/profiles?uuid=" + uuidDashless))
                    .timeout(Duration.ofSeconds(20))
                    .header("API-Key", apiKey.trim())
                    .GET().build();
            HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) { Msg.LOG.warn("[HypixelApi] profiles HTTP {}", resp.statusCode()); return null; }
            JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
            if (!root.has("profiles") || root.get("profiles").isJsonNull()) return null;

            JsonObject selected = null;
            for (JsonElement pe : root.getAsJsonArray("profiles")) {
                JsonObject p = pe.getAsJsonObject();
                if (p.has("selected") && p.get("selected").getAsBoolean()) { selected = p; break; }
                if (selected == null) selected = p; // fall back to first
            }
            if (selected == null) return null;

            JsonObject members = selected.getAsJsonObject("members");
            JsonObject member = members.has(uuidDashless) ? members.getAsJsonObject(uuidDashless) : null;
            if (member == null) return null;

            Set<String> tiers = new HashSet<>();
            if (member.has("unlocked_coll_tiers") && member.get("unlocked_coll_tiers").isJsonArray()) {
                JsonArray arr = member.getAsJsonArray("unlocked_coll_tiers");
                for (JsonElement t : arr) tiers.add(t.getAsString());
            }
            cacheUuid = uuidDashless; cacheTiers = tiers; cacheAt = System.currentTimeMillis();
            Msg.LOG.info("[HypixelApi] {} unlocked collection tiers", tiers.size());
            return tiers;
        } catch (Exception e) {
            Msg.LOG.warn("[HypixelApi] fetch failed: {}", e.toString());
            return null;
        }
    }

    /**
     * Current Sack contents for the player's selected profile, item id -&gt; count (e.g.
     * {@code WORM_MEMBRANE -> 4300}). Several SkyBlock drops (worm-fishing's Worm Membrane among them)
     * go straight into a Sack rather than the physical inventory, so this is the only live source for
     * them -- there's no chat/action-bar message and no way to see the count without either opening the
     * Sacks menu or reading it here. Returns null on failure (no key, network error, no profile).
     * Blocking -- worker thread only. Cached per-UUID briefly ({@link #SACKS_TTL_MS}), independently of
     * {@link #unlockedCollTiers}'s longer-lived cache, since sack counts change on every catch.
     */
    public static Map<String, Long> sackCounts(String uuidDashless, String apiKey) {
        return sackCounts(uuidDashless, apiKey, false);
    }

    /** As {@link #sackCounts(String, String)}; {@code fresh} skips the short-lived cache (one-shot calculators). */
    public static Map<String, Long> sackCounts(String uuidDashless, String apiKey, boolean fresh) {
        if (uuidDashless == null || apiKey == null || apiKey.isBlank()) return null;
        Map<String, Long> cached = sacksCache;
        if (!fresh && cached != null && uuidDashless.equals(sacksCacheUuid) && System.currentTimeMillis() - sacksCacheAt < SACKS_TTL_MS) {
            return cached;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(
                            URI.create("https://api.hypixel.net/v2/skyblock/profiles?uuid=" + uuidDashless))
                    .timeout(Duration.ofSeconds(20))
                    .header("API-Key", apiKey.trim())
                    .GET().build();
            HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) { Msg.LOG.warn("[HypixelApi] profiles HTTP {}", resp.statusCode()); return null; }
            JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
            if (!root.has("profiles") || root.get("profiles").isJsonNull()) return null;

            JsonObject selected = null;
            for (JsonElement pe : root.getAsJsonArray("profiles")) {
                JsonObject p = pe.getAsJsonObject();
                if (p.has("selected") && p.get("selected").getAsBoolean()) { selected = p; break; }
                if (selected == null) selected = p; // fall back to first
            }
            if (selected == null) return null;

            JsonObject members = selected.getAsJsonObject("members");
            JsonObject member = members.has(uuidDashless) ? members.getAsJsonObject(uuidDashless) : null;
            if (member == null) return null;

            Map<String, Long> counts = new HashMap<>();
            // Hypixel moved this under member.inventory.sacks_counts (the old top-level key is gone, which
            // made every sack read as empty); fall back to the legacy location just in case.
            JsonObject sacks = null;
            if (member.has("inventory") && member.get("inventory").isJsonObject()) {
                JsonObject inv = member.getAsJsonObject("inventory");
                if (inv.has("sacks_counts") && inv.get("sacks_counts").isJsonObject()) sacks = inv.getAsJsonObject("sacks_counts");
            }
            if (sacks == null && member.has("sacks_counts") && member.get("sacks_counts").isJsonObject()) {
                sacks = member.getAsJsonObject("sacks_counts");
            }
            if (sacks != null) {
                for (Map.Entry<String, JsonElement> e : sacks.entrySet()) {
                    if (e.getValue().isJsonPrimitive()) counts.put(e.getKey(), e.getValue().getAsLong());
                }
            }
            sacksCacheUuid = uuidDashless; sacksCache = counts; sacksCacheAt = System.currentTimeMillis();
            return counts;
        } catch (Exception e) {
            Msg.LOG.warn("[HypixelApi] sacks fetch failed: {}", e.toString());
            return null;
        }
    }

    /**
     * True if the player has {@code collectionKey} at {@code level} or higher. Tier strings look like
     * {@code KEY_level} where KEY may itself contain underscores (e.g. INK_SACK_5), so match the
     * numeric suffix. Returns false if tiers is null (unknown → conservative).
     */
    public static boolean isCollectionUnlocked(Set<String> tiers, String collectionKey, int level) {
        if (tiers == null || collectionKey == null) return false;
        String prefix = collectionKey + "_";
        for (String t : tiers) {
            if (!t.startsWith(prefix)) continue;
            try {
                if (Integer.parseInt(t.substring(prefix.length())) >= level) return true;
            } catch (NumberFormatException ignored) {}
        }
        return false;
    }
}
