package cc.serverlist.featured.api;

import cc.serverlist.featured.FeaturedClient;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * How often the mod talks to serverlist.cc on its own: play stats batches, friends refreshes and
 * presence. Two things can slow it down without a new jar:
 *
 * <ul>
 *   <li><b>The API's say-so.</b> {@code GET /v1/featured/config} (Cloudflare-cached, so asking costs
 *       the server nothing) carries the intervals, and {@code pause: true} stops all of it. Read at
 *       launch and every ten minutes after.</li>
 *   <li><b>Back-off.</b> A 429 or 503 from the API stops background calls for its Retry-After, or
 *       for 30 s doubling per answer in a row up to 30 minutes, spread at random so every client
 *       doesn't come back in the same second.</li>
 * </ul>
 *
 * Things the player clicks (sending a request, accepting one) are never held back; only what the
 * mod does by itself is.
 */
public final class Pacing {
    private static final long CONFIG_EVERY_MS = 10 * 60_000L;
    private static final long MIN_MS = 15_000L;
    private static final long MAX_MS = 6 * 60 * 60_000L;
    private static final long BACKOFF_FIRST_MS = 30_000L;
    private static final long BACKOFF_MAX_MS = 30 * 60_000L;

    private static volatile long statsMs = 60_000L;
    private static volatile long friendsPageMs = 30_000L;
    private static volatile long friendsGameMs = 60_000L;
    private static volatile long presenceMs = 120_000L;
    private static volatile boolean paused;

    private static volatile long backoffUntil;
    private static int strikes;
    private static volatile long configAt = Long.MIN_VALUE / 2;
    private static volatile boolean configLoading;

    private Pacing() {
    }

    public static long statsMs() {
        return statsMs;
    }

    public static long friendsPageMs() {
        return friendsPageMs;
    }

    public static long friendsGameMs() {
        return friendsGameMs;
    }

    public static long presenceMs() {
        return presenceMs;
    }

    /** False while the API has asked for quiet, or is backing us off. */
    public static boolean allowed() {
        return !paused && System.currentTimeMillis() >= backoffUntil;
    }

    /** Every answer from the API goes through here: 429 and 503 back off, anything else resets. */
    public static synchronized void observe(int status, String retryAfter) {
        if (status == 429 || status == 503) {
            strikes = Math.min(strikes + 1, 16);
            long wait = Math.min(BACKOFF_MAX_MS, BACKOFF_FIRST_MS << Math.min(strikes - 1, 10));
            if (retryAfter != null) {
                try {
                    wait = Math.min(BACKOFF_MAX_MS, Math.max(1, Long.parseLong(retryAfter.trim())) * 1000L);
                } catch (NumberFormatException ignored) {
                    // An HTTP date or junk: the doubling stands.
                }
            }
            // 75% to 125% of it, so a crowd that was refused together doesn't return together.
            wait = (long) (wait * (0.75 + ThreadLocalRandom.current().nextDouble() * 0.5));
            backoffUntil = Math.max(backoffUntil, System.currentTimeMillis() + wait);
            FeaturedClient.LOGGER.info("serverlist.cc is busy (HTTP {}); background requests wait {} s", status, wait / 1000);
        } else if (status / 100 == 2) {
            strikes = 0;
        }
    }

    /** Fetches the API's pacing if it is ten minutes old. Cheap to call often; it never blocks. */
    public static void maybeRefresh() {
        long now = System.currentTimeMillis();
        if (configLoading || now - configAt < CONFIG_EVERY_MS) {
            return;
        }
        configLoading = true;
        configAt = now;
        CompletableFuture.runAsync(() -> {
            try {
                ServerListApi.Response r = ServerListApi.send("GET", ServerListApi.BASE + "/v1/featured/config", null, false, null,
                        java.time.Duration.ofSeconds(15));
                if (r.status() == 200) {
                    apply(JsonParser.parseString(r.body()).getAsJsonObject());
                }
            } catch (Exception e) {
                FeaturedClient.LOGGER.debug("Pacing config not read: {}", e.toString());
            } finally {
                configLoading = false;
            }
        });
    }

    /** Takes the intervals the API sent; anything missing or out of range keeps its value. */
    static void apply(JsonObject c) {
        statsMs = seconds(c, "statsSec", statsMs);
        friendsPageMs = seconds(c, "friendsSec", friendsPageMs);
        friendsGameMs = seconds(c, "friendsGameSec", friendsGameMs);
        presenceMs = seconds(c, "presenceSec", presenceMs);
        JsonElement p = c.get("pause");
        paused = p != null && p.isJsonPrimitive() && p.getAsBoolean();
        if (paused) {
            FeaturedClient.LOGGER.info("serverlist.cc asked for quiet: play stats and friends updates are paused");
        } else if (!c.isEmpty()) {
            FeaturedClient.LOGGER.info("Pacing from serverlist.cc: stats every {} s, friends {} s / {} s in game, presence {} s",
                    statsMs / 1000, friendsPageMs / 1000, friendsGameMs / 1000, presenceMs / 1000);
        }
    }

    private static long seconds(JsonObject c, String key, long fallback) {
        JsonElement e = c.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        return Math.max(MIN_MS, Math.min(MAX_MS, e.getAsLong() * 1000L));
    }
}
