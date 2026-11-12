package com.fish5.automation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Live snapshot of Hypixel Bazaar order books for <b>all</b> products (not just
 * shards). Used by the wormfish profit tracker for Gemstone Mixture cost.
 *
 * <p>Hypixel's naming is inverted relative to the player's action:
 * <ul>
 *   <li>{@code sell_summary} holds the standing <b>buy orders</b> (what buyers offer),
 *       highest first — so {@code sell_summary[0].pricePerUnit} is the top buy order
 *       you must beat by +0.1 to jump the queue. This is {@link Quote#topBuyOrder()}.</li>
 *   <li>{@code buy_summary} holds the standing <b>sell offers</b> (what you'd instant-buy
 *       from), cheapest first — {@code buy_summary[0].pricePerUnit} is the instant-buy
 *       price, {@link Quote#instaBuy()}.</li>
 * </ul>
 */
public final class BazaarQuotes {

    private static final String URL = "https://api.hypixel.net/v2/skyblock/bazaar";

    private static volatile BazaarQuotes instance;
    private static volatile long fetchedAt;

    /**
     * One product's relevant prices, in coins. A missing side is 0. {@code offerPrices}/
     * {@code offerAmounts} are the standing sell-offer book (Hypixel {@code buy_summary}),
     * cheapest first — used to count supply undercutting a player's sell offer.
     */
    public record Quote(String id, double topBuyOrder, double instaBuy,
                        double[] offerPrices, long[] offerAmounts) {

        /**
         * Total units of standing sell offers priced strictly below {@code price} — i.e. how many
         * items are "available for less" than a player's own sell offer (their live competition).
         * Relies on {@code buy_summary} being cheapest-first, so it stops at the first level >= price.
         */
        public long unitsCheaperThan(double price) {
            long sum = 0;
            for (int i = 0; i < offerPrices.length; i++) {
                if (offerPrices[i] < price - 1e-6) sum += offerAmounts[i];
                else break;
            }
            return sum;
        }
    }

    private final Map<String, Quote> byId;

    private BazaarQuotes(Map<String, Quote> byId) {
        this.byId = byId;
    }

    public static BazaarQuotes current() {
        return instance;
    }

    public static long fetchedAt() {
        return fetchedAt;
    }

    /** Blocking HTTP fetch (worker thread only). Caches on success; leaves the old snapshot on failure. */
    public static BazaarQuotes fetch() {
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
                Msg.LOG.warn("[BazaarQuotes] HTTP {}", resp.statusCode());
                return instance;
            }
            JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
            if (!root.has("success") || !root.get("success").getAsBoolean() || !root.has("products")) {
                Msg.LOG.warn("[BazaarQuotes] API returned success=false or no products");
                return instance;
            }
            JsonObject products = root.getAsJsonObject("products");
            Map<String, Quote> map = new HashMap<>();
            for (Map.Entry<String, JsonElement> e : products.entrySet()) {
                JsonObject p = e.getValue().getAsJsonObject();
                double topBuyOrder = firstPricePerUnit(p, "sell_summary");
                double instaBuy = firstPricePerUnit(p, "buy_summary");
                JsonArray offers = p.has("buy_summary") ? p.getAsJsonArray("buy_summary") : new JsonArray();
                int n = offers.size();
                double[] offerPrices = new double[n];
                long[] offerAmounts = new long[n];
                for (int i = 0; i < n; i++) {
                    JsonObject lvl = offers.get(i).getAsJsonObject();
                    offerPrices[i] = lvl.has("pricePerUnit") ? lvl.get("pricePerUnit").getAsDouble() : 0;
                    offerAmounts[i] = lvl.has("amount") ? lvl.get("amount").getAsLong() : 0;
                }
                map.put(e.getKey(), new Quote(e.getKey(), topBuyOrder, instaBuy, offerPrices, offerAmounts));
            }
            BazaarQuotes fresh = new BazaarQuotes(map);
            instance = fresh;
            fetchedAt = System.currentTimeMillis();
            Msg.LOG.info("[BazaarQuotes] Loaded {} bazaar products", map.size());
            return fresh;
        } catch (Exception ex) {
            Msg.LOG.warn("[BazaarQuotes] fetch failed: {}", ex.toString());
            return instance;
        }
    }

    private static double firstPricePerUnit(JsonObject product, String key) {
        if (!product.has(key)) return 0;
        JsonArray arr = product.getAsJsonArray(key);
        if (arr.isEmpty()) return 0;
        JsonObject top = arr.get(0).getAsJsonObject();
        return top.has("pricePerUnit") ? top.get("pricePerUnit").getAsDouble() : 0;
    }

    /** Quote for a bazaar product id (e.g. {@code ENCHANTED_CARROT}), or null if not on the bazaar. */
    public Quote quote(String id) {
        return id == null ? null : byId.get(id);
    }
}
