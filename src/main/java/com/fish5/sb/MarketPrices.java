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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Price lookups pulled from the public Coflnet SkyBlock API
 * (no key required). Two independent queries per item:
 *
 * <ul>
 *   <li><b>Buy side</b> — the young item you place in the pocket is an <i>auction-house BIN</i>.
 *       {@link #cheapestBins} pulls every active BIN for a tag, expands stacks to a per-unit price
 *       ({@code startingBid / count}), sorts cheapest-first and sums the <b>N cheapest units</b>.
 *       Summing the individual cheapest listings (rather than {@code N × lowest}) is deliberate: it
 *       models buying up the book, so a lone 1.5m undercut sitting under a wall of 3m listings costs
 *       1.5m + 3m = 4.5m for two, not 3m.</li>
 *   <li><b>Sell side</b> — the aged/refined item trades on the <i>bazaar</i>.
 *       {@link #bazaarWeekAvg} averages the last 7 days of history so a spiked current price can't
 *       skew the plan. {@code offerSide} picks the sell-offer price (Coflnet {@code buy}, what a
 *       standing sell offer eventually fills at) versus the insta-sell price (Coflnet {@code sell},
 *       the top buy order).</li>
 * </ul>
 *
 * <p>Used by {@link com.fish5.features.impl.skyblock.WormfishProfitTracker}
 * for items (like Gemstone Chamber) that trade on the <i>auction house</i> rather than the bazaar --
 * see {@link #ahWeekAvg}, the AH equivalent of {@link #bazaarWeekAvg}.
 *
 * <p>All calls are blocking and must run off the render thread.
 */
public final class MarketPrices {

    private static final String BIN_URL = "https://sky.coflnet.com/api/auctions/tag/%s/active/bin";
    private static final String BAZAAR_WEEK_URL = "https://sky.coflnet.com/api/bazaar/%s/history/week";
    private static final String AH_WEEK_URL = "https://sky.coflnet.com/api/item/price/%s/history/week";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private MarketPrices() {}

    /** Result of summing the cheapest auction-house BINs for a tag. */
    public record BinResult(double sumCost, double cheapest, int wanted, int available) {
        /** True when the book held at least as many units as requested. */
        public boolean enough() { return available >= wanted; }
    }

    /**
     * Sum of the {@code count} cheapest per-unit BIN prices for {@code tag}. If fewer than
     * {@code count} units are listed, sums everything available (see {@link BinResult#available}).
     * Returns {@code null} on any network/parse failure.
     */
    public static BinResult cheapestBins(String tag, int count) {
        try {
            JsonElement parsed = get(String.format(BIN_URL, tag));
            if (parsed == null || !parsed.isJsonArray()) return null;
            JsonArray arr = parsed.getAsJsonArray();

            List<Double> unitPrices = new ArrayList<>();
            for (JsonElement el : arr) {
                JsonObject a = el.getAsJsonObject();
                if (a.has("bin") && !a.get("bin").getAsBoolean()) continue;
                double price = a.has("startingBid") ? a.get("startingBid").getAsDouble() : 0;
                if (price <= 0) continue;
                int stack = a.has("count") ? Math.max(1, a.get("count").getAsInt()) : 1;
                double perUnit = price / stack;
                for (int i = 0; i < stack; i++) unitPrices.add(perUnit);
            }
            if (unitPrices.isEmpty()) return new BinResult(0, 0, count, 0);

            Collections.sort(unitPrices);
            int take = Math.min(count, unitPrices.size());
            double sum = 0;
            for (int i = 0; i < take; i++) sum += unitPrices.get(i);
            return new BinResult(sum, unitPrices.get(0), count, unitPrices.size());
        } catch (Exception ex) {
            Msg.LOG.warn("[MarketPrices] BIN fetch failed for {}: {}", tag, ex.toString());
            return null;
        }
    }

    /**
     * Cheapest single-item BIN for {@code tag}, skipping auctions by {@code sellerUuid} (dashless, may be
     * null) so a relist never undercuts our own listing. -1 when nothing else is listed or on failure.
     */
    public static double lowestBinExcluding(String tag, String sellerUuid) {
        try {
            JsonElement parsed = get(String.format(BIN_URL, tag));
            if (parsed == null || !parsed.isJsonArray()) return -1;
            String self = sellerUuid == null ? "" : sellerUuid.replace("-", "");
            double best = -1;
            for (JsonElement el : parsed.getAsJsonArray()) {
                JsonObject a = el.getAsJsonObject();
                if (a.has("bin") && !a.get("bin").getAsBoolean()) continue;
                if (a.has("auctioneerId") && a.get("auctioneerId").getAsString().replace("-", "").equalsIgnoreCase(self)) continue;
                double price = a.has("startingBid") ? a.get("startingBid").getAsDouble() : 0;
                int stack = a.has("count") ? Math.max(1, a.get("count").getAsInt()) : 1;
                if (price <= 0) continue;
                double perUnit = price / stack;
                if (best < 0 || perUnit < best) best = perUnit;
            }
            return best;
        } catch (Exception ex) {
            Msg.LOG.warn("[MarketPrices] BIN fetch failed for {}: {}", tag, ex.toString());
            return -1;
        }
    }

    /**
     * Median of the last 7 days of hourly auction-house prices for {@code tag} (Coflnet's {@code avg}
     * per bucket). Less swayed by a few spiked hours than {@link #ahWeekAvg}. -1 on failure.
     */
    public static double ahWeekMedian(String tag) {
        try {
            JsonElement parsed = get(String.format(AH_WEEK_URL, tag));
            if (parsed == null || !parsed.isJsonArray()) return -1;
            List<Double> values = new ArrayList<>();
            for (JsonElement el : parsed.getAsJsonArray()) {
                JsonObject o = el.getAsJsonObject();
                if (!o.has("avg")) continue;
                double v = o.get("avg").getAsDouble();
                if (v > 0) values.add(v);
            }
            if (values.isEmpty()) return -1;
            Collections.sort(values);
            int n = values.size();
            return n % 2 == 1 ? values.get(n / 2) : (values.get(n / 2 - 1) + values.get(n / 2)) / 2.0;
        } catch (Exception ex) {
            Msg.LOG.warn("[MarketPrices] AH history fetch failed for {}: {}", tag, ex.toString());
            return -1;
        }
    }

    /**
     * 7-day average bazaar price for {@code tag}. {@code offerSide} true averages the sell-offer
     * price ({@code buy}); false averages the insta-sell price ({@code sell}). Returns -1 on failure
     * or when the tag has no bazaar history.
     */
    public static double bazaarWeekAvg(String tag, boolean offerSide) {
        try {
            JsonElement parsed = get(String.format(BAZAAR_WEEK_URL, tag));
            if (parsed == null || !parsed.isJsonArray()) return -1;
            JsonArray arr = parsed.getAsJsonArray();
            String field = offerSide ? "buy" : "sell";
            double sum = 0;
            int n = 0;
            for (JsonElement el : arr) {
                JsonObject o = el.getAsJsonObject();
                if (!o.has(field)) continue;
                double v = o.get(field).getAsDouble();
                if (v <= 0) continue;
                sum += v;
                n++;
            }
            return n == 0 ? -1 : sum / n;
        } catch (Exception ex) {
            Msg.LOG.warn("[MarketPrices] bazaar fetch failed for {}: {}", tag, ex.toString());
            return -1;
        }
    }

    /**
     * 7-day average auction-house price for {@code tag} -- for items (like Gemstone Chamber) that
     * trade on the AH rather than the bazaar, so {@link #bazaarWeekAvg} can't be used. Coflnet exposes
     * the same hourly-bucket shape for AH items as it does for bazaar history, just keyed by {@code avg}
     * instead of {@code buy}/{@code sell}; averaged the same unweighted way as {@link #bazaarWeekAvg}
     * for consistency. Returns -1 on failure or when the tag has no AH history.
     */
    public static double ahWeekAvg(String tag) {
        try {
            JsonElement parsed = get(String.format(AH_WEEK_URL, tag));
            if (parsed == null || !parsed.isJsonArray()) return -1;
            JsonArray arr = parsed.getAsJsonArray();
            double sum = 0;
            int n = 0;
            for (JsonElement el : arr) {
                JsonObject o = el.getAsJsonObject();
                if (!o.has("avg")) continue;
                double v = o.get("avg").getAsDouble();
                if (v <= 0) continue;
                sum += v;
                n++;
            }
            return n == 0 ? -1 : sum / n;
        } catch (Exception ex) {
            Msg.LOG.warn("[MarketPrices] AH history fetch failed for {}: {}", tag, ex.toString());
            return -1;
        }
    }

    /** Blocking GET returning the parsed JSON body, or null on non-200 / error. */
    private static JsonElement get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "fish5-prices")
                .GET()
                .build();
        HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            Msg.LOG.warn("[MarketPrices] HTTP {} for {}", resp.statusCode(), url);
            return null;
        }
        return JsonParser.parseString(resp.body());
    }
}
