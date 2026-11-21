package cc.serverlist.featured.telemetry;

import cc.serverlist.featured.compat.Compat;
import cc.serverlist.featured.FeaturedClient;
import cc.serverlist.featured.api.Pacing;
import cc.serverlist.featured.api.ServerListApi;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.exceptions.AuthenticationException;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

/**
 * What the mod reports back to serverlist.cc, and how.
 *
 * <p>Events are queued on the client thread and sent in batches by one background thread: every
 * minute, when the queue grows past a page's worth, and at exit. Identity is not a field in the
 * batch: before the first batch the sender proves who is playing the way a server login does —
 * a nonce from the API, {@code joinServer} against Mojang with that nonce as the server id, and
 * the API asks Mojang {@code hasJoined} — and is handed a token that goes with every batch for
 * a week. A client without a real Mojang session (offline mode, the dev launcher) cannot finish
 * that and sends nothing.
 *
 * <p>The queue and the open play session are written to {@code serverlist-cache/telemetry.json}
 * each tick, so a crash still reports the session it cut short, on the next launch.
 *
 * <p>Turning it off ({@link #setEnabled}) drops the queue, asks the API to delete everything it
 * holds for this player, and forgets the token. Nothing is sent again until it is turned on.
 */
public final class Telemetry {
    private static final Path STATE = FabricLoader.getInstance().getGameDir().resolve("serverlist-cache").resolve("telemetry.json");
    private static final long TICK_MS = 15_000;
    /** When the last batch went (or was due); the next waits Pacing.statsMs() after it. */
    private static long lastFlushAt = System.currentTimeMillis();
    private static final int FLUSH_AT = 200;
    /** With nothing to report, an empty batch still goes every this many ticks: "still here". */
    private static final int HEARTBEAT_TICKS = 2;
    private static final int QUEUE_CAP = 2000;
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration EXIT_TIMEOUT = Duration.ofSeconds(3);
    /** After a failed identity proof (no session, Mojang down), don't try again for this long. */
    private static final long IDENTITY_RETRY_MS = 30 * 60_000;

    /** An attempt to join, from the click until the login packet or the failure screen. */
    private record Attempt(String address, String name, String slug, String via, String row, int pos, boolean hero, long startedAt) {
    }

    /** A play session: from the login packet until the connection goes. */
    private static final class Session {
        final String address;
        String slug;
        final String via;
        final long startedAt;
        long lastSeenAt;
        final List<Integer> latencies = new ArrayList<>();

        Session(String address, String slug, String via, long startedAt) {
            this.address = address;
            this.slug = slug;
            this.via = via;
            this.startedAt = startedAt;
            this.lastSeenAt = startedAt;
        }
    }

    private static final Object LOCK = new Object();
    private static TelemetryConfig config;
    private static final Deque<JsonObject> queue = new ArrayDeque<>();
    private static String token;
    private static long tokenExpiresAt;
    private static long identityFailedAt = Long.MIN_VALUE;
    private static Attempt attempt;
    private static Session session;
    /** Set by the page right before it starts a connection, read by the ConnectScreen hook. */
    private static JsonObject pageJoin;
    /** Set by the Multiplayer screen's direct-connect path, read by the ConnectScreen hook. */
    public static boolean directJoin;
    private static ScheduledExecutorService sender;
    private static boolean started;
    private static int idleTicks;

    private Telemetry() {
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /** Reads the config and any state a previous run left, and starts the sender. Once. */
    public static void start() {
        synchronized (LOCK) {
            if (started) {
                return;
            }
            started = true;
            config = TelemetryConfig.load();
            restore();
        }
        sender = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "serverlist-telemetry");
            t.setDaemon(true);
            return t;
        });
        // Ticks often; whether a batch goes is Pacing's call (the API can stretch or pause it).
        sender.scheduleWithFixedDelay(Telemetry::tick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(Telemetry::onExit, "serverlist-telemetry-exit"));
        FeaturedClient.LOGGER.info("Play stats {}", config.telemetry ? "on (config/serverlist.json to turn off)" : "off");
    }

    public static TelemetryConfig config() {
        return config;
    }

    public static boolean enabled() {
        return config != null && config.telemetry;
    }

    /** The player's switch. Off also asks the API to forget everything it has for them. */
    public static void setEnabled(boolean on) {
        String forgetWith;
        synchronized (LOCK) {
            if (config.telemetry == on) {
                return;
            }
            config.telemetry = on;
            config.save();
            forgetWith = on ? null : token;
            if (!on) {
                queue.clear();
                token = null;
                attempt = null;
                session = null;
                persist();
            }
        }
        FeaturedClient.LOGGER.info("Play stats turned {}", on ? "on" : "off");
        if (!on) {
            // With no token in hand (never proven, or expired), prove identity once more so the
            // deletion still happens: turning stats off has to mean the data goes.
            sender.execute(() -> {
                try {
                    String with = forgetWith != null ? forgetWith : ensureToken();
                    if (with == null) {
                        FeaturedClient.LOGGER.warn("Could not prove identity to ask serverlist.cc to forget this player");
                        return;
                    }
                    ServerListApi.Response r = ServerListApi.post(ServerListApi.BASE + "/v1/telemetry/forget", new byte[0], false, with, SEND_TIMEOUT);
                    if (r.status() == 401 && forgetWith != null) {
                        dropToken();
                        String fresh = ensureToken();
                        if (fresh != null) {
                            r = ServerListApi.post(ServerListApi.BASE + "/v1/telemetry/forget", new byte[0], false, fresh, SEND_TIMEOUT);
                        }
                    }
                    FeaturedClient.LOGGER.info("Asked serverlist.cc to forget this player: HTTP {}", r.status());
                } catch (IOException e) {
                    FeaturedClient.LOGGER.warn("Could not reach serverlist.cc to forget this player: {}", e.toString());
                }
            });
        }
    }

    /** Writes the settings after the settings screen changed one, under the same lock as the rest. */
    public static void saveConfig() {
        synchronized (LOCK) {
            config.save();
        }
    }

    public static void markNoticeSeen() {
        synchronized (LOCK) {
            if (!config.noticeSeen) {
                config.noticeSeen = true;
                config.save();
            }
        }
    }

    // ------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------

    /**
     * Queue one event. {@code fields} alternates name and value; a null value is left out.
     * Values are strings, numbers or booleans — the API drops anything else, and there is no
     * free text in any event on purpose.
     */
    public static void event(String type, Object... fields) {
        if (!enabled()) {
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("t", System.currentTimeMillis());
        o.addProperty("e", type);
        for (int i = 0; i + 1 < fields.length; i += 2) {
            put(o, (String) fields[i], fields[i + 1]);
        }
        boolean flushNow;
        synchronized (LOCK) {
            if (queue.size() >= QUEUE_CAP) {
                queue.pollFirst();
            }
            queue.addLast(o);
            flushNow = queue.size() >= FLUSH_AT;
        }
        if (flushNow && sender != null && Pacing.allowed()) {
            sender.execute(Telemetry::flush);
        }
    }

    private static void put(JsonObject o, String key, Object value) {
        switch (value) {
            case null -> {
            }
            case String s -> o.addProperty(key, s);
            case Number n -> o.addProperty(key, n);
            case Boolean b -> o.addProperty(key, b);
            default -> o.addProperty(key, value.toString());
        }
    }

    // ------------------------------------------------------------------
    // Joins and sessions
    // ------------------------------------------------------------------

    /** The page is about to connect to one of its cards; the ConnectScreen hook reads this. */
    public static void pageJoin(String slug, String row, int pos, boolean hero) {
        JsonObject o = new JsonObject();
        put(o, "slug", slug);
        put(o, "row", row);
        o.addProperty("pos", pos);
        o.addProperty("hero", hero);
        synchronized (LOCK) {
            pageJoin = o;
        }
    }

    /**
     * ConnectScreen.startConnecting: a join is starting, from wherever the player was. Tracked
     * whether or not stats are shared — the local "Recently played" row is built from it; only
     * the events are gated.
     */
    public static void joinStarting(String address, String name, String via) {
        JsonObject page;
        synchronized (LOCK) {
            page = pageJoin;
            pageJoin = null;
            directJoin = false;
        }
        String slug = page != null && page.has("slug") ? page.get("slug").getAsString() : ServerListApi.slugFor(address);
        if (slug == null) {
            // Not on the page and not in what has been fetched: ask for the full list now, so by
            // the time the session ends the server can be named if the tracker knows it.
            ServerListApi.servers(false);
        }
        Attempt a = new Attempt(address, name, slug, via,
                page != null && page.has("row") ? page.get("row").getAsString() : null,
                page != null ? page.get("pos").getAsInt() : -1,
                page != null && page.get("hero").getAsBoolean(),
                System.currentTimeMillis());
        synchronized (LOCK) {
            // Joining somewhere else from inside a server (the page from the pause menu, the
            // list): the player's own choice, so it is not a transfer.
            if (session != null) {
                endSession("switched");
            }
            attempt = a;
        }
        event("join_attempt", "slug", a.slug(), "via", a.via(), "row", a.row(),
                "pos", a.pos() >= 0 ? a.pos() : null, "hero", page != null ? a.hero() : null);
    }

    /** ClientPacketListener.handleLogin: the server let the player in. */
    public static void loginSucceeded(int protocol) {
        Attempt a;
        synchronized (LOCK) {
            a = attempt;
            attempt = null;
            // In at last, after any transfer: leaving from here is a leave again.
            transferUntil = 0;
            if (a == null) {
                return; // the far end of a transfer mid-session: same session
            }
            String slug = a.slug() != null ? a.slug() : ServerListApi.slugFor(a.address());
            session = new Session(a.address(), slug, a.via(), System.currentTimeMillis());
            persist();
        }
        RecentServers.joined(a.address(), session.slug, a.name());
        long ms = System.currentTimeMillis() - a.startedAt();
        event("join_result", "slug", session.slug, "via", a.via(), "outcome", "ok", "connectMs", ms, "protocol", protocol);
    }

    /** Any disconnect with a reason: a failed join, a kick, a lost connection. */
    public static void disconnected(Component reason) {
        String key = translationKey(reason);
        String text = reason == null ? "" : reason.getString().toLowerCase(Locale.ROOT);
        // The raw reason goes to the local log only, so the categories below can be tuned
        // against what servers actually say; it is never sent.
        if (attempt != null || session != null) {
            FeaturedClient.LOGGER.info("Disconnect reason [{}]: {}", key.isEmpty() ? "text" : key, reason == null ? "" : reason.getString());
        }
        synchronized (LOCK) {
            if (attempt != null && key.equals("disconnect.transfer")) {
                // Moved to another machine before the first one let the player in (Minekeep does
                // this on every join): the join is still under way, so the attempt carries over
                // to the connection the transfer makes.
                transferUntil = System.currentTimeMillis() + TRANSFER_GRACE_MS;
                return;
            }
            if (attempt != null) {
                Attempt a = attempt;
                attempt = null;
                String slug = a.slug() != null ? a.slug() : ServerListApi.slugFor(a.address());
                event("join_result", "slug", slug, "via", a.via(), "outcome", outcomeOf(key, text));
                return;
            }
            if (session != null && key.equals("disconnect.transfer")) {
                // The server is moving the player to another of its machines (Minekeep and
                // other hosts do this on join). Same server as far as anyone is concerned: the
                // session carries on under the address the player joined, and the connection
                // that follows is not a new join (ConnectScreenMixin skips transfers).
                transferUntil = System.currentTimeMillis() + TRANSFER_GRACE_MS;
                return;
            }
            if (session != null) {
                endSession(endOf(key, text));
            }
        }
    }

    /** How long a transfer may take before the session it interrupted counts as over. */
    private static final long TRANSFER_GRACE_MS = 30_000;
    /** Until this time a disconnect that tears the world down is the transfer, not a leave. */
    private static long transferUntil;

    /**
     * Minecraft.disconnect: the player left, or the game is tearing the world down. The game
     * also calls this while cleaning up a failed join, before the failure screen — so a pending
     * attempt is left alone here for the reason that follows it.
     */
    /**
     * The page is about to leave the server the player is on for another one. Ends the session
     * as a switch now, before the disconnect that follows would record it as a quit.
     */
    public static void switchingServers() {
        synchronized (LOCK) {
            if (session != null) {
                endSession("switched");
            }
        }
    }

    public static void leftWorld() {
        synchronized (LOCK) {
            // Before 1.21.11 the world teardown of a transfer arrives here like any leave.
            if (session != null && System.currentTimeMillis() < transferUntil) {
                return;
            }
            if (session != null) {
                endSession("quit");
            }
        }
    }

    /** Must hold LOCK. */
    private static void endSession(String end) {
        Session s = session;
        session = null;
        if (s.slug == null) {
            s.slug = ServerListApi.slugFor(s.address);
        }
        long seconds = Math.max(0, (System.currentTimeMillis() - s.startedAt) / 1000);
        Integer latency = median(s.latencies);
        persist();
        RecentServers.played(s.address, seconds);
        event("session_end", "slug", s.slug, "via", s.via, "duration", seconds, "end", end, "latency", latency);
    }

    private static Integer median(List<Integer> values) {
        if (values.isEmpty()) {
            return null;
        }
        List<Integer> sorted = new ArrayList<>(values);
        sorted.sort(null);
        return sorted.get(sorted.size() / 2);
    }

    private static String translationKey(Component c) {
        if (c != null && c.getContents() instanceof TranslatableContents t) {
            return t.getKey();
        }
        return "";
    }

    /** Why a join failed, as a category. The text is looked at, never sent. */
    static String outcomeOf(String key, String text) {
        if (key.equals("disconnect.unknownHost") || text.contains("unknown host") || text.contains("cannot resolve")) return "unknown_host";
        if (key.equals("connect.aborted")) return "aborted";
        if (key.equals("multiplayer.disconnect.outdated_client") || key.equals("multiplayer.disconnect.incompatible")) return "outdated_client";
        if (key.equals("multiplayer.disconnect.outdated_server")) return "outdated_server";
        if (key.equals("multiplayer.disconnect.not_whitelisted") || text.contains("whitelist")) return "whitelist";
        if (key.startsWith("multiplayer.disconnect.banned") || text.contains("banned") || text.contains("you are ban")) return "banned";
        if (key.equals("multiplayer.disconnect.server_full") || text.contains("server is full") || text.contains("full")) return "full";
        if (key.startsWith("disconnect.loginFailed") || key.equals("multiplayer.disconnect.unverified_username")
                || key.equals("multiplayer.disconnect.authservers_down") || text.contains("authentication")) return "auth";
        if (text.contains("maintenance")) return "maintenance";
        if (text.contains("resource pack") || text.contains("resourcepack")) return "resourcepack";
        if (key.equals("disconnect.timeout") || key.equals("multiplayer.disconnect.slow_login") || text.contains("timed out")) return "timeout";
        if (text.contains("refused") || text.contains("no route")) return "refused";
        if (text.contains("logged in from another location") || text.contains("already connected") || text.contains("already logged")) return "duplicate_login";
        if (text.contains("fallback server") || text.contains("default server") || text.contains("kicked whilst connecting")
                || text.contains("could not connect to") || text.contains("unable to connect you to")) return "proxy";
        if (text.contains("throttl") || text.contains("too fast") || text.contains("wait before") || text.contains("reconnecting too")) return "throttle";
        if (text.contains("restart") || text.contains("server closed") || text.contains("shutting down") || text.contains("reboot")) return "restart";
        if (text.contains("internal exception") || text.contains("io.netty") || text.contains("connection reset")
                || text.contains("ssl") || text.contains("handshake")) return "network";
        if (key.equals("disconnect.lost") || key.equals("disconnect.endOfStream")) return "lost";
        if (key.startsWith("multiplayer.disconnect.") || key.equals("disconnect.kicked")) return "kicked";
        if (text.contains("outdated") || text.contains("unsupported") || text.contains("version")) return "outdated_client";
        return "other";
    }

    /** How a session ended, as a category. */
    static String endOf(String key, String text) {
        if (key.equals("disconnect.timeout") || text.contains("timed out")) return "timeout";
        if (text.contains("resource pack") || text.contains("resourcepack")) return "resourcepack";
        if (text.contains("logged in from another location") || text.contains("already connected") || text.contains("already logged")) return "duplicate_login";
        if (key.equals("multiplayer.disconnect.idling") || text.contains("afk") || text.contains("idle") || text.contains("inactiv")) return "afk";
        if (key.startsWith("multiplayer.disconnect.banned") || text.contains("banned") || text.contains("you are ban")) return "banned";
        if (text.contains("restart") || text.contains("server closed") || text.contains("shutting down") || text.contains("reboot")
                || key.equals("multiplayer.disconnect.server_shutdown")) return "restart";
        if (text.contains("fallback server") || text.contains("default server") || text.contains("proxy lost")
                || text.contains("could not connect to") || text.contains("lost connection to")) return "proxy";
        if (text.contains("throttl") || text.contains("too fast")) return "throttle";
        if (text.contains("internal exception") || text.contains("io.netty") || text.contains("connection reset")) return "network";
        if (key.equals("disconnect.lost") || key.equals("disconnect.endOfStream") || key.equals("disconnect.disconnected")
                || text.contains("lost")) return "lost";
        if (key.equals("disconnect.transfer")) return "transfer";
        if (key.equals("disconnect.quitting")) return "quit";
        if (key.startsWith("multiplayer.disconnect.") || key.equals("disconnect.kicked") || key.equals("disconnect.genericReason")
                || text.contains("kick")) return "kicked";
        return "other";
    }

    // ------------------------------------------------------------------
    // Sender
    // ------------------------------------------------------------------

    private static void tick() {
        try {
            sampleLatency();
            synchronized (LOCK) {
                if (session != null) {
                    session.lastSeenAt = System.currentTimeMillis();
                    // Joined before the address index arrived (or from outside the page): try
                    // again, so friends see the server rather than "unlisted" for the whole stay.
                    if (session.slug == null) {
                        session.slug = ServerListApi.slugFor(session.address);
                    }
                }
                persist();
            }
            Pacing.maybeRefresh();
            long now = System.currentTimeMillis();
            if (now - lastFlushAt >= Pacing.statsMs() && Pacing.allowed()) {
                lastFlushAt = now;
                flush();
            }
        } catch (Exception e) {
            FeaturedClient.LOGGER.debug("Telemetry tick: {}", e.toString());
        }
    }

    /** The player's own tab-list latency, as the server measures it. Read on the client thread. */
    private static void sampleLatency() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || session == null) {
            return;
        }
        mc.execute(() -> {
            ClientPacketListener conn = mc.getConnection();
            if (conn == null) {
                return;
            }
            PlayerInfo me = conn.getPlayerInfo(mc.getUser().getProfileId());
            if (me == null || me.getLatency() <= 0) {
                return;
            }
            synchronized (LOCK) {
                if (session != null && session.latencies.size() < 600) {
                    session.latencies.add(me.getLatency());
                }
            }
        });
    }

    /** Send what is queued. Runs on the sender thread; on failure the queue is kept for next time. */
    private static void flush() {
        if (!enabled()) {
            return;
        }
        List<JsonObject> batch;
        String playing;
        synchronized (LOCK) {
            if (queue.isEmpty()) {
                // Nothing happened; say so every other minute, so the count of who is
                // playing right now stays right while someone sits on a server for hours.
                if (++idleTicks < HEARTBEAT_TICKS) {
                    return;
                }
            }
            idleTicks = 0;
            batch = new ArrayList<>(queue);
            playing = session != null ? session.slug : null;
        }
        String bearer = ensureToken();
        if (bearer == null) {
            return;
        }
        JsonObject body = new JsonObject();
        body.addProperty("install", config.install);
        body.add("client", clientEnvelope());
        put(body, "playing", playing);
        JsonArray events = new JsonArray();
        for (JsonObject e : batch) {
            events.add(e);
        }
        body.add("events", events);
        try {
            ServerListApi.Response r = ServerListApi.post(ServerListApi.BASE + "/v1/telemetry/events", gzip(body.toString()), true, bearer, SEND_TIMEOUT);
            if (r.status() == 401) {
                synchronized (LOCK) {
                    token = null;
                }
                FeaturedClient.LOGGER.debug("Telemetry token rejected; will prove identity again");
                return;
            }
            if (r.status() / 100 != 2 && r.status() != 413) {
                FeaturedClient.LOGGER.debug("Telemetry batch: HTTP {}", r.status());
                return;
            }
            synchronized (LOCK) {
                // Only what was sent: events queued meanwhile stay for the next batch.
                for (int i = 0; i < batch.size() && !queue.isEmpty(); i++) {
                    queue.pollFirst();
                }
                persist();
            }
            FeaturedClient.LOGGER.debug("Telemetry: sent {} events ({})", batch.size(), r.body());
        } catch (IOException e) {
            FeaturedClient.LOGGER.debug("Telemetry batch failed: {}", e.toString());
        }
    }

    /**
     * The same proven identity, for friends: a bearer token for this player, or null without a
     * Mojang session. Works whether play stats are on or off. Blocks; call it off the client thread.
     */
    public static String identityToken() {
        return ensureToken();
    }

    /** The API turned the token down: prove identity again next time. */
    public static void dropToken() {
        synchronized (LOCK) {
            token = null;
        }
    }

    /**
     * The server id joined at Mojang for one proof: a hash of the API's nonce under our own
     * prefix, never the nonce itself. Joining whatever the API sent would let anyone answering
     * the handshake pass in a real server's login hash and log in to that server as this player.
     * Mirrors serverIdFor in the API's lib/telemetryAuth.mjs.
     */
    static String serverIdFor(String nonce) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-1")
                    .digest(("serverlist.cc/discovery:" + nonce).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(d);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The token for this player, proving identity first if there is none. Sender thread. */
    private static String ensureToken() {
        synchronized (LOCK) {
            if (token != null && System.currentTimeMillis() < tokenExpiresAt - 60_000) {
                return token;
            }
            if (System.currentTimeMillis() < identityFailedAt + IDENTITY_RETRY_MS) {
                return null;
            }
        }
        Minecraft mc = Minecraft.getInstance();
        User user = mc.getUser();
        try {
            ServerListApi.Response hs = ServerListApi.post(ServerListApi.BASE + "/v1/telemetry/handshake", new byte[0], false, null, SEND_TIMEOUT);
            if (hs.status() != 200) {
                throw new IOException("handshake HTTP " + hs.status());
            }
            String nonce = JsonParser.parseString(hs.body()).getAsJsonObject().get("nonce").getAsString();
            if (FeaturedClient.devEnv("SERVERLIST_TELEMETRY_NOJOIN") == null) {
                Compat.sessionService(mc).joinServer(user.getProfileId(), user.getAccessToken(), serverIdFor(nonce));
            }
            JsonObject req = new JsonObject();
            req.addProperty("uuid", user.getProfileId().toString());
            req.addProperty("name", user.getName());
            req.addProperty("nonce", nonce);
            ServerListApi.Response res = ServerListApi.post(ServerListApi.BASE + "/v1/telemetry/session", req.toString().getBytes(StandardCharsets.UTF_8), false, null, SEND_TIMEOUT);
            if (res.status() != 200) {
                throw new IOException("session HTTP " + res.status());
            }
            JsonObject o = JsonParser.parseString(res.body()).getAsJsonObject();
            synchronized (LOCK) {
                token = o.get("token").getAsString();
                tokenExpiresAt = java.time.Instant.parse(o.get("expiresAt").getAsString()).toEpochMilli();
                persist();
                return token;
            }
        } catch (AuthenticationException e) {
            // No Mojang session to prove: offline mode, or the dev launcher. Nothing is sent.
            FeaturedClient.LOGGER.info("Play stats: no Mojang session to verify ({}); nothing will be sent", e.getMessage());
        } catch (Exception e) {
            FeaturedClient.LOGGER.debug("Play stats: identity not proven: {}", e.toString());
        }
        synchronized (LOCK) {
            identityFailedAt = System.currentTimeMillis();
        }
        return null;
    }

    private static JsonObject clientEnvelope() {
        Minecraft mc = Minecraft.getInstance();
        JsonObject c = new JsonObject();
        c.addProperty("mod", FabricLoader.getInstance().getModContainer(FeaturedClient.MOD_ID)
                .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?"));
        c.addProperty("mc", SharedConstants.getCurrentVersion().name());
        c.addProperty("loader", "fabric");
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        c.addProperty("os", os.contains("win") ? "windows" : os.contains("mac") ? "macos" : os.contains("linux") ? "linux" : "other");
        c.addProperty("lang", mc.options.languageCode);
        c.addProperty("gui", mc.getWindow().getGuiScale());
        c.addProperty("win", mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight());
        c.addProperty("mods", FabricLoader.getInstance().getAllMods().size());
        c.addProperty("fapi", FabricLoader.getInstance().isModLoaded("fabric-api"));
        return c;
    }

    private static byte[] gzip(String s) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(s.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    /** Must hold LOCK. Written small and often; a failure is a debug line, never a crash. */
    private static void persist() {
        JsonObject o = new JsonObject();
        put(o, "token", token);
        o.addProperty("tokenExpiresAt", tokenExpiresAt);
        JsonArray q = new JsonArray();
        for (JsonObject e : queue) {
            q.add(e);
        }
        o.add("queue", q);
        if (session != null) {
            JsonObject s = new JsonObject();
            s.addProperty("address", session.address);
            put(s, "slug", session.slug);
            s.addProperty("via", session.via);
            s.addProperty("startedAt", session.startedAt);
            s.addProperty("lastSeenAt", session.lastSeenAt);
            o.add("session", s);
        }
        try {
            Files.createDirectories(STATE.getParent());
            Files.writeString(STATE, o.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            FeaturedClient.LOGGER.debug("Telemetry state not written: {}", e.toString());
        }
    }

    /** Must hold LOCK. A session left open by the last run is reported as cut short. */
    private static void restore() {
        if (!Files.isRegularFile(STATE)) {
            return;
        }
        try {
            JsonObject o = JsonParser.parseString(Files.readString(STATE, StandardCharsets.UTF_8)).getAsJsonObject();
            if (o.has("token")) {
                token = o.get("token").getAsString();
                tokenExpiresAt = o.get("tokenExpiresAt").getAsLong();
            }
            if (o.has("queue")) {
                for (JsonElement e : o.getAsJsonArray("queue")) {
                    if (queue.size() < QUEUE_CAP) {
                        queue.addLast(e.getAsJsonObject());
                    }
                }
            }
            if (o.has("session") && config.telemetry) {
                JsonObject s = o.getAsJsonObject("session");
                long seconds = Math.max(0, (s.get("lastSeenAt").getAsLong() - s.get("startedAt").getAsLong()) / 1000);
                JsonObject ev = new JsonObject();
                ev.addProperty("t", s.get("lastSeenAt").getAsLong());
                ev.addProperty("e", "session_end");
                put(ev, "slug", s.has("slug") ? s.get("slug").getAsString() : null);
                ev.addProperty("via", s.get("via").getAsString());
                ev.addProperty("duration", seconds);
                ev.addProperty("end", "crashed");
                queue.addLast(ev);
            }
        } catch (Exception e) {
            FeaturedClient.LOGGER.debug("Telemetry state not read: {}", e.toString());
        }
        persist();
    }

    /** JVM exit: close the session, write the state, and try once, briefly, to send. */
    private static void onExit() {
        synchronized (LOCK) {
            if (session != null) {
                endSession("quit");
            }
            persist();
        }
        if (!enabled()) {
            return;
        }
        List<JsonObject> batch;
        String bearer;
        synchronized (LOCK) {
            if (queue.isEmpty() || token == null || System.currentTimeMillis() >= tokenExpiresAt) {
                return;
            }
            batch = new ArrayList<>(queue);
            bearer = token;
        }
        try {
            JsonObject body = new JsonObject();
            body.addProperty("install", config.install);
            JsonArray events = new JsonArray();
            for (JsonObject e : batch) {
                events.add(e);
            }
            body.add("events", events);
            ServerListApi.Response r = ServerListApi.post(ServerListApi.BASE + "/v1/telemetry/events", gzip(body.toString()), true, bearer, EXIT_TIMEOUT);
            if (r.status() / 100 == 2) {
                synchronized (LOCK) {
                    queue.clear();
                    persist();
                }
            }
        } catch (Exception ignored) {
            // The state file has it; the next launch sends it.
        }
    }
}
