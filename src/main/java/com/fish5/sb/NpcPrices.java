package com.fish5.sb;

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
import java.util.Map;

/**
 * Snapshot of every SkyBlock item's fixed NPC sell price, pulled from the public
 * {@code resources/skyblock/items} endpoint (no API key required). This is the
 * "sell to NPC" price of an item — the Bazaar API does not expose it, so we
 * cache it here and only re-fetch occasionally (NPC prices are static).
 *
 * <p>Keyed by SkyBlock item id (e.g. {@code ENCHANTED_CARROT}), which matches the
 * Bazaar product id for the vast majority of flippable items and the {@code id}
 * tag stored on the item stack in the Bazaar menu (see
 * {@link SkyblockData#skyblockId}).
 */
public final class NpcPrices {

    private static final String URL = "https://api.hypixel.net/v2/resources/skyblock/items";

    /** Item ids are static; only refetch if the cache is older than this. */
    private static final long TTL_MS = Duration.ofHours(6).toMillis();

    private static volatile NpcPrices instance;
    private static volatile long fetchedAt;

    /**
     * Bundled NPC sell prices for farming crops (raw + enchanted), from Hypixel's official item data
     * (the same figures the wiki lists). Used as an always-available, instant fallback so crop flips
     * resolve the moment you track them, without waiting on the live fetch. Keyed by SkyBlock item id.
     */
    private static final Map<String, Long> CROP_PRICES = new HashMap<>();

    static {
        CROP_PRICES.put("WHEAT", 6L);
        CROP_PRICES.put("SEEDS", 3L);
        CROP_PRICES.put("CARROT_ITEM", 3L);
        CROP_PRICES.put("POTATO_ITEM", 3L);
        CROP_PRICES.put("PUMPKIN", 10L);
        CROP_PRICES.put("MELON", 2L);
        CROP_PRICES.put("SUGAR_CANE", 4L);
        CROP_PRICES.put("CACTUS", 4L);
        CROP_PRICES.put("INK_SACK:3", 3L);            // cocoa beans
        CROP_PRICES.put("RED_MUSHROOM", 10L);
        CROP_PRICES.put("BROWN_MUSHROOM", 10L);
        CROP_PRICES.put("NETHER_STALK", 4L);           // nether wart
        CROP_PRICES.put("ENCHANTED_WHEAT", 960L);
        CROP_PRICES.put("ENCHANTED_SEEDS", 480L);
        CROP_PRICES.put("ENCHANTED_CARROT", 480L);
        CROP_PRICES.put("ENCHANTED_POTATO", 480L);
        CROP_PRICES.put("ENCHANTED_PUMPKIN", 1600L);
        CROP_PRICES.put("ENCHANTED_MELON", 320L);
        CROP_PRICES.put("ENCHANTED_MELON_BLOCK", 51200L);
        CROP_PRICES.put("ENCHANTED_SUGAR", 640L);
        CROP_PRICES.put("ENCHANTED_SUGAR_CANE", 102400L);
        CROP_PRICES.put("ENCHANTED_CACTUS_GREEN", 640L);
        CROP_PRICES.put("ENCHANTED_CACTUS", 102400L);
        CROP_PRICES.put("ENCHANTED_COCOA", 480L);
        CROP_PRICES.put("ENCHANTED_RED_MUSHROOM", 1600L);
        CROP_PRICES.put("ENCHANTED_BROWN_MUSHROOM", 1600L);
        CROP_PRICES.put("ENCHANTED_HUGE_MUSHROOM_1", 256000L);   // red
        CROP_PRICES.put("ENCHANTED_HUGE_MUSHROOM_2", 256000L);   // brown
        CROP_PRICES.put("ENCHANTED_NETHER_STALK", 640L);
        CROP_PRICES.put("MUTANT_NETHER_STALK", 102400L);
        CROP_PRICES.put("ENCHANTED_HAY_BALE", 153600L);
    }

    /** Bundled crop NPC sell price for an item id, or -1 if it isn't a known farming crop. */
    public static long cropPrice(String id) {
        Long v = id == null ? null : CROP_PRICES.get(id);
        return v == null ? -1 : v;
    }

    private final Map<String, Long> byId;

    private NpcPrices(Map<String, Long> byId) {
        this.byId = byId;
    }

    /** Last successfully fetched snapshot, or null if none yet. */
    public static NpcPrices current() {
        return instance;
    }

    /** True when we have no snapshot or the cached one has expired. */
    public static boolean isStale() {
        return instance == null || System.currentTimeMillis() - fetchedAt > TTL_MS;
    }

    /** Blocking HTTP fetch (worker thread only). Caches the result on success; leaves the old one on failure. */
    public static NpcPrices fetch() {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(URL))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "fish5-npcflip")
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                Msg.LOG.warn("[NpcPrices] HTTP {}", resp.statusCode());
                return instance;
            }
            JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
            if (!root.has("success") || !root.get("success").getAsBoolean() || !root.has("items")) {
                Msg.LOG.warn("[NpcPrices] API returned success=false or no items");
                return instance;
            }
            Map<String, Long> map = new HashMap<>();
            for (JsonElement el : root.getAsJsonArray("items")) {
                JsonObject item = el.getAsJsonObject();
                if (!item.has("id") || !item.has("npc_sell_price")) continue;
                String id = item.get("id").getAsString();
                long price = (long) Math.floor(item.get("npc_sell_price").getAsDouble());
                if (price > 0) map.put(id, price);
            }
            NpcPrices fresh = new NpcPrices(map);
            instance = fresh;
            fetchedAt = System.currentTimeMillis();
            Msg.LOG.info("[NpcPrices] Loaded {} NPC sell prices", map.size());
            return fresh;
        } catch (Exception ex) {
            Msg.LOG.warn("[NpcPrices] fetch failed: {}", ex.toString());
            return instance;
        }
    }

    /** NPC sell price in coins for a SkyBlock item id, or -1 if the item can't be sold to an NPC. */
    public long npcSellPrice(String id) {
        Long v = id == null ? null : byId.get(id);
        return v == null ? -1 : v;
    }
}
