package cc.serverlist.featured.api;

import cc.serverlist.featured.FeaturedClient;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;

/**
 * The read side of serverlist.cc's public API, with the caching the page needs.
 *
 * <p>Everything is fetched off-thread and handed back as a future; callers hop to the render
 * thread themselves. The page itself is {@code /v1/featured}, ~50 KB and held for two minutes;
 * the full list behind search is ~700 KB and only fetched once someone types, then held for
 * five; icons never change for a given hash and are held forever.
 */
public final class ServerListApi {
    /** The API root; {@code SERVERLIST_API} in the environment points a dev client elsewhere. */
    public static final String BASE = java.util.Objects.requireNonNullElse(
            cc.serverlist.featured.FeaturedClient.devEnv("SERVERLIST_API"), "https://api.serverlist.cc");
    private static final Duration FEATURED_TTL = Duration.ofMinutes(2);
    private static final Duration LIST_TTL = Duration.ofMinutes(5);
    private static final Duration HISTORY_TTL = Duration.ofMinutes(5);
    /** Icons and banners are also kept on disk, so a relaunch does not refetch the page's art. */
    private static final Duration DISK_TTL = Duration.ofDays(7);
    private static final Path CACHE_DIR = FabricLoader.getInstance().getGameDir().resolve("serverlist-cache");
    private static final String USER_AGENT = "serverlist-featured/0.1 (Minecraft mod; +https://serverlist.cc)";

    // The fetch pool blocks on HttpClient.send, so the client must not share it: with one pool
    // a page's worth of icon and banner requests fills every thread with waiting senders and the
    // client has nothing left to complete them on. It gets its own (default) executor instead.
    private static final ExecutorService POOL = Executors.newFixedThreadPool(12, r -> {
        Thread t = new Thread(r, "serverlist-api");
        t.setDaemon(true);
        return t;
    });
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final Object LOCK = new Object();
    private static CompletableFuture<Featured> featured;
    private static Instant featuredFetchedAt = Instant.EPOCH;
    private static CompletableFuture<List<TrackedServer>> servers;
    private static Instant serversFetchedAt = Instant.EPOCH;
    private static final Map<String, CompletableFuture<byte[]>> blobs = new HashMap<>();
    private static final Map<String, CompletableFuture<History>> histories = new HashMap<>();
    private static final Map<String, Instant> historyFetchedAt = new HashMap<>();

    private ServerListApi() {
    }

    /** The page: hero, rows and the servers they show, as the API lays it out. */
    public static CompletableFuture<Featured> featured(boolean force) {
        synchronized (LOCK) {
            boolean stale = Instant.now().isAfter(featuredFetchedAt.plus(FEATURED_TTL));
            boolean failed = featured != null && featured.isCompletedExceptionally();
            if (featured == null || force || stale || failed) {
                featuredFetchedAt = Instant.now();
                featured = CompletableFuture.supplyAsync(ServerListApi::fetchFeatured, POOL);
            }
            return featured;
        }
    }

    /** The most cards one row page asks for: about a dozen screens at a normal width. */
    public static final int ROW_PAGE = 60;

    /**
     * The next stretch of a row, from {@code offset}, in row order. Not cached: each page is
     * asked for once, when the strip nears the end of what it has, and appended to the row.
     */
    public static CompletableFuture<List<TrackedServer>> rowPage(String rowId, int offset) {
        return CompletableFuture.supplyAsync(() -> fetchRowPage(rowId, offset), POOL);
    }

    /** Every online Java server the tracker lists, cracked ones flagged, most players first — for search and browsing. */
    public static CompletableFuture<List<TrackedServer>> servers(boolean force) {
        synchronized (LOCK) {
            boolean stale = Instant.now().isAfter(serversFetchedAt.plus(LIST_TTL));
            boolean failed = servers != null && servers.isCompletedExceptionally();
            if (servers == null || force || stale || failed) {
                serversFetchedAt = Instant.now();
                servers = CompletableFuture.supplyAsync(ServerListApi::fetchServers, POOL);
            }
            return servers;
        }
    }

    /**
     * The tracker's slug for a server address, from whatever lists are already in hand — the
     * page, and the full list once it has been fetched — or null for a server it does not track
     * (or has not heard of yet). {@code address} is {@code host[:port]} as the player typed it.
     */
    public static String slugFor(String address) {
        String wanted = normalizeAddress(address);
        if (wanted == null) {
            return null;
        }
        CompletableFuture<Featured> page;
        CompletableFuture<List<TrackedServer>> all;
        synchronized (LOCK) {
            page = featured;
            all = servers;
        }
        if (page != null && page.isDone() && !page.isCompletedExceptionally()) {
            Featured f = page.join();
            if (f.hero() != null && wanted.equals(normalizeAddress(f.hero().address()))) {
                return f.hero().slug();
            }
            for (TrackedServer s : f.servers()) {
                if (wanted.equals(normalizeAddress(s.address()))) {
                    return s.slug();
                }
            }
        }
        if (all != null && all.isDone() && !all.isCompletedExceptionally()) {
            for (TrackedServer s : all.join()) {
                if (wanted.equals(normalizeAddress(s.address()))) {
                    return s.slug();
                }
            }
        }
        // Every tracked server, not just the page's: the exact address, then the same site
        // (play.example.net and example.net are one network; the busiest listing wins).
        String exact = byAddress.get(wanted);
        if (exact != null) {
            return exact;
        }
        String site = siteKey(wanted);
        String bySiteSlug = site == null ? null : bySite.get(site);
        if (bySiteSlug != null) {
            return bySiteSlug;
        }
        warmAddressIndex();
        return null;
    }

    /** A network's own listings share a slug up to the "-2" the tracker adds to repeats. */
    private static String network(String slug) {
        return slug.replaceAll("-\\d+$", "");
    }

    private static volatile Map<String, String> byAddress = Map.of();
    private static volatile Map<String, String> bySite = Map.of();
    private static volatile long addressIndexAt = Long.MIN_VALUE / 2;
    private static volatile boolean addressIndexLoading;
    private static final long ADDRESS_INDEX_TTL_MS = 6 * 60 * 60_000L;
    private static final long ADDRESS_INDEX_RETRY_MS = 10 * 60_000L;

    /**
     * Fetches the index of every tracked server's address ({@code /v1/featured/addresses},
     * ~50 KB, Cloudflare-cached) unless it is fresh. Called at launch and whenever a lookup
     * misses. The address the player joined is never sent anywhere: the match is made here.
     */
    public static void warmAddressIndex() {
        long now = System.currentTimeMillis();
        long wait = byAddress.isEmpty() ? ADDRESS_INDEX_RETRY_MS : ADDRESS_INDEX_TTL_MS;
        if (addressIndexLoading || now - addressIndexAt < wait) {
            return;
        }
        addressIndexLoading = true;
        addressIndexAt = now;
        POOL.execute(() -> {
            try {
                JsonArray rows = JsonParser.parseString(new String(get(BASE + "/v1/featured/addresses"), StandardCharsets.UTF_8))
                        .getAsJsonObject().getAsJsonArray("servers");
                Map<String, String> exact = new HashMap<>(rows.size() * 2);
                Map<String, String> site = new HashMap<>(rows.size() * 2);
                // A site with two different networks under it is a host handing out subdomains
                // (one the list above doesn't know yet): no guessing by site there.
                java.util.Set<String> mixed = new java.util.HashSet<>();
                for (JsonElement el : rows) {
                    JsonArray r = el.getAsJsonArray();
                    String address = normalizeAddress(r.get(0).getAsString());
                    String slug = r.get(1).getAsString();
                    if (address == null || slug.isEmpty()) {
                        continue;
                    }
                    // Busiest first, so the first listing under a name is the one to keep.
                    exact.putIfAbsent(address, slug);
                    String key = siteKey(address);
                    if (key != null) {
                        String had = site.putIfAbsent(key, slug);
                        if (had != null && !network(had).equals(network(slug))) {
                            mixed.add(key);
                        }
                    }
                }
                mixed.forEach(site::remove);
                byAddress = Map.copyOf(exact);
                bySite = Map.copyOf(site);
                FeaturedClient.LOGGER.info("Address index from serverlist.cc: {} servers", exact.size());
            } catch (Exception e) {
                FeaturedClient.LOGGER.debug("Address index not read: {}", e.toString());
            } finally {
                addressIndexLoading = false;
            }
        });
    }

    /** {@code host[:port]} lower-cased, the default port dropped, so two spellings compare equal. */
    public static String normalizeAddress(String address) {
        if (address == null) {
            return null;
        }
        String a = address.trim().toLowerCase(Locale.ROOT);
        if (a.endsWith(":25565")) {
            a = a.substring(0, a.length() - 6);
        }
        return a.isEmpty() ? null : a;
    }

    /**
     * Hosts where the subdomain is the server: a shared hosting provider hands every customer
     * a name under its own domain, so {@code a.minehut.gg} and {@code b.minehut.gg} are strangers.
     */
    private static final Set<String> SHARED_HOSTS = Set.of(
            "minehut.gg", "minehut.com", "aternos.me", "exaroton.me", "apexmc.co", "falixsrv.me", "falixserver.net",
            "ploudos.com", "playit.gg", "mcserverhost.com", "serv.nu", "mcpro.io", "pebble.host", "bisecthosting.com",
            "shockbyte.com", "nodecraft.com", "gg.gg", "ddns.net", "duckdns.org", "no-ip.org", "hopto.org",
            "minekeep.gg", "mcsh.io");

    /**
     * What makes two addresses the same server: the registrable domain, so {@code tarna.hypixel.net}
     * and {@code mc.hypixel.net} both come out as {@code hypixel.net}. Networks answer on any
     * subdomain, and players save whichever one they were told. IP literals and names under a
     * shared host keep everything; the port is dropped either way.
     */
    public static String siteKey(String address) {
        String a = normalizeAddress(address);
        if (a == null) {
            return null;
        }
        String host = a;
        int colon = host.lastIndexOf(':');
        if (colon > 0 && host.indexOf(':') == colon) {
            host = host.substring(0, colon);
        }
        if (host.indexOf(':') >= 0 || host.matches("[0-9.]+")) {
            return host; // an IP literal
        }
        String[] labels = host.split("\\.");
        int n = labels.length;
        if (n <= 2) {
            return host;
        }
        // Two labels, or three for the likes of co.uk / com.au.
        int keep = labels[n - 1].length() == 2 && labels[n - 2].matches("co|com|net|org|ac|gov|edu|me") ? 3 : 2;
        if (keep >= n) {
            return host;
        }
        String parent = String.join(".", Arrays.copyOfRange(labels, n - keep, n));
        if (SHARED_HOSTS.contains(parent)) {
            return host;
        }
        return parent;
    }

    /** A POST's outcome: the status and whatever came back, for the caller to read or ignore. */
    public record Response(int status, String body) {
    }

    /**
     * POST JSON and wait. Runs on the calling thread — the telemetry sender has its own — and
     * never throws for an HTTP status; a transport failure is an {@link IOException}.
     */
    public static Response post(String url, byte[] body, boolean gzipped, String bearer, Duration timeout) throws IOException {
        return send("POST", url, body, gzipped, bearer, timeout);
    }

    /** {@link #post}, for any method; a GET sends no body. */
    public static Response send(String method, String url, byte[] body, boolean gzipped, String bearer, Duration timeout) throws IOException {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        if (body != null) {
            req.header("Content-Type", "application/json");
        }
        if (gzipped) {
            req.header("Content-Encoding", "gzip");
        }
        if (bearer != null) {
            req.header("Authorization", "Bearer " + bearer);
        }
        try {
            HttpResponse<String> res = HTTP.send(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Pacing.observe(res.statusCode(), res.headers().firstValue("Retry-After").orElse(null));
            return new Response(res.statusCode(), res.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    /** Raw bytes of an icon by its API path ({@code /v1/icons/<hash>}). */
    public static CompletableFuture<byte[]> icon(String path) {
        return bytes(BASE + path);
    }

    /**
     * Raw bytes of any image URL, kept on disk for a week. Two asks for the same URL while it
     * is in flight share one fetch; once it lands the bytes are not held in memory, since the
     * texture made from them is what the page keeps, and a second ask reads the disk copy.
     */
    public static CompletableFuture<byte[]> bytes(String url) {
        synchronized (LOCK) {
            CompletableFuture<byte[]> f = blobs.get(url);
            if (f == null) {
                CompletableFuture<byte[]> fetch = CompletableFuture.supplyAsync(() -> cachedGet(url), POOL);
                blobs.put(url, fetch);
                fetch.whenComplete((b, err) -> {
                    synchronized (LOCK) {
                        blobs.remove(url, fetch);
                    }
                });
                f = fetch;
            }
            return f;
        }
    }

    private static byte[] cachedGet(String url) {
        Path file = CACHE_DIR.resolve(sha1(url));
        try {
            if (Files.isRegularFile(file)
                    && Files.getLastModifiedTime(file).toInstant().isAfter(Instant.now().minus(DISK_TTL))) {
                return Files.readAllBytes(file);
            }
        } catch (IOException ignored) {
            // A bad cache file is just a miss.
        }
        byte[] body = get(url);
        try {
            if (body.length == 0) {
                throw new IOException("empty body");
            }
            Files.createDirectories(CACHE_DIR);
            Files.write(file, body);
        } catch (IOException e) {
            FeaturedClient.LOGGER.debug("Could not cache {}: {}", url, e.toString());
        }
        return body;
    }

    private static String sha1(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format(Locale.ROOT, "%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A server's player counts over a range, oldest first: {@code at} is each bucket's time and
     * {@code avg} its average, {@code -1} for a gap. The buckets are what the tracker keeps at
     * that range — minutes for a day, five minutes for a week, hours beyond — and the series
     * starts wherever tracking did, so it can be shorter than the range asked for.
     */
    public record History(String range, long[] at, int[] avg) {
        public int size() {
            return avg.length;
        }
    }

    /** The ranges the history route answers, as it spells them. */
    public static final String[] HISTORY_RANGES = {"24h", "7d", "30d", "90d"};

    /** A server's history at one range, cached for a few minutes per slug and range. */
    public static CompletableFuture<History> history(String slug, String range) {
        String key = slug + "?" + range;
        synchronized (LOCK) {
            CompletableFuture<History> f = histories.get(key);
            Instant at = historyFetchedAt.getOrDefault(key, Instant.EPOCH);
            if (f == null || f.isCompletedExceptionally() || Instant.now().isAfter(at.plus(HISTORY_TTL))) {
                f = CompletableFuture.supplyAsync(() -> fetchHistory(slug, range), POOL);
                histories.put(key, f);
                historyFetchedAt.put(key, Instant.now());
            }
            return f;
        }
    }

    // ------------------------------------------------------------------

    private static Featured fetchFeatured() {
        String json = new String(get(BASE + "/v1/featured"), StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        // `servers` is keyed by slug; the rows and the hero refer into it.
        Map<String, TrackedServer> bySlug = new HashMap<>();
        List<TrackedServer> servers = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("servers").entrySet()) {
            TrackedServer s = parseServer(e.getValue().getAsJsonObject());
            if (s != null) {
                bySlug.put(s.slug(), s);
                servers.add(s);
            }
        }
        servers.sort(Comparator.comparingInt(TrackedServer::players).reversed());

        List<Featured.Row> rows = new ArrayList<>();
        for (JsonElement el : root.getAsJsonArray("rows")) {
            JsonObject o = el.getAsJsonObject();
            List<TrackedServer> members = new ArrayList<>();
            for (JsonElement slug : o.getAsJsonArray("servers")) {
                TrackedServer s = bySlug.get(slug.getAsString());
                if (s != null) {
                    members.add(s);
                }
            }
            String id = str(o, "id");
            String title = str(o, "title");
            String subtitle = str(o, "subtitle");
            if (id != null && title != null && !members.isEmpty()) {
                rows.add(new Featured.Row(id, title, subtitle == null ? "" : subtitle, num(o, "total", members.size()), members));
            }
        }
        String heroSlug = str(root, "hero");
        TrackedServer hero = heroSlug == null ? null : bySlug.get(heroSlug);
        FeaturedClient.LOGGER.info("Fetched the featured page from serverlist.cc: {} rows, {} servers", rows.size(), servers.size());
        return new Featured(hero, List.copyOf(rows), List.copyOf(servers));
    }

    private static List<TrackedServer> fetchRowPage(String rowId, int offset) {
        String json = new String(get(BASE + "/v1/featured/rows/" + rowId + "?offset=" + offset + "&limit=" + ROW_PAGE), StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        List<TrackedServer> out = new ArrayList<>();
        for (JsonElement el : root.getAsJsonArray("servers")) {
            TrackedServer s = parseServer(el.getAsJsonObject());
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }

    private static List<TrackedServer> fetchServers() {
        String json = new String(get(BASE + "/v1/servers"), StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray rows = root.getAsJsonArray("servers");
        List<TrackedServer> out = new ArrayList<>(rows.size());
        for (JsonElement el : rows) {
            JsonObject o = el.getAsJsonObject();
            if (!"java".equals(str(o, "edition")) || !bool(o, "online")) {
                continue;
            }
            // Cracked servers are kept and flagged: `cracked` is the tracker's login probe
            // answering "no Mojang account needed" (or the MOTD saying so). The page leaves them
            // out unless the player turns them on in Sort & filter; null means not known, and
            // those count as premium rather than punishing a server for an unanswered question.
            TrackedServer s = parseServer(o);
            if (s != null) {
                out.add(s);
            }
        }
        out.sort(Comparator.comparingInt(TrackedServer::players).reversed());
        FeaturedClient.LOGGER.info("Fetched {} online Java servers from serverlist.cc for search ({} cracked)", out.size(), out.stream().filter(TrackedServer::cracked).count());
        return out;
    }

    /**
     * One server from either document. The featured one carries tags, colour, banners and a
     * website; the plain list doesn't, and those come back empty, 0 and null.
     */
    private static TrackedServer parseServer(JsonObject o) {
        String host = str(o, "host");
        String slug = str(o, "slug");
        if (host == null || slug == null) {
            return null;
        }
        int port = num(o, "port", 25565);
        String address = str(o, "address");
        if (address == null) {
            address = port == 25565 ? host : host + ":" + port;
        }
        // `peak` is a number on /v1/featured and an object on /v1/servers.
        JsonElement peakEl = o.get("peak");
        int peak = peakEl != null && peakEl.isJsonObject() ? num(peakEl.getAsJsonObject(), "players", 0) : num(o, "peak", 0);
        String name = str(o, "name");
        List<String> tags = new ArrayList<>();
        if (o.has("tags") && o.get("tags").isJsonArray()) {
            for (JsonElement t : o.getAsJsonArray("tags")) {
                if (t.isJsonPrimitive()) {
                    tags.add(t.getAsString());
                }
            }
        }
        JsonObject banner = o.has("banner") && o.get("banner").isJsonObject() ? o.getAsJsonObject("banner") : null;
        // /v1/featured flattens what the owner set; /v1/servers keeps it under `owner`, so a
        // server found by search carries its links too rather than only a featured one.
        JsonObject owner = o.has("owner") && o.get("owner").isJsonObject() ? o.getAsJsonObject("owner") : null;
        return new TrackedServer(
                slug,
                Categories.clean(name == null || name.isBlank() ? host : name),
                host,
                port,
                address,
                true,
                num(o, "players", 0),
                num(o, "maxPlayers", 0),
                Categories.clean(str(o, "version")),
                Categories.clean(str(o, "motd")),
                path(str(o, "icon")),
                peak,
                num(o, "dayAgo", -1),
                num(o, "weekAgo", -1),
                epoch(str(o, "addedAt")),
                List.copyOf(tags),
                color(str(o, "color")),
                banner == null ? null : path(str(banner, "card")),
                banner == null ? null : path(str(banner, "wide")),
                banner == null ? null : path(str(banner, "hero")),
                pick(o, owner, "website"),
                pick(o, owner, "discord"),
                pick(o, owner, "store"),
                blankToNull(Categories.clean(pick(o, owner, "description"))),
                bool(o, "cracked")
        );
    }

    /** "" is "nothing here", not a value: the page tests these fields for null. */
    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** A field the API may send flat or inside the owner block. */
    private static String pick(JsonObject o, JsonObject owner, String key) {
        String v = str(o, key);
        return v != null || owner == null ? v : str(owner, key);
    }

    /** {@code #RRGGBB} as opaque ARGB, or 0 for none or nonsense. */
    private static int color(String hex) {
        if (hex == null || !hex.matches("#[0-9a-fA-F]{6}")) {
            return 0;
        }
        return 0xFF000000 | Integer.parseInt(hex.substring(1), 16);
    }

    private static History fetchHistory(String slug, String range) {
        String json = new String(get(BASE + "/v1/history/" + slug + "?range=" + range), StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray points = root.getAsJsonArray("points");
        int[] avg = new int[points.size()];
        long[] at = new long[points.size()];
        for (int i = 0; i < avg.length; i++) {
            JsonObject p = points.get(i).getAsJsonObject();
            avg[i] = num(p, "avg", -1);
            at[i] = epoch(str(p, "t"));
        }
        return new History(range, at, avg);
    }

    private static byte[] get(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json, image/*;q=0.9, */*;q=0.5")
                    .header("Accept-Encoding", "gzip")
                    .GET()
                    .build();
            HttpResponse<byte[]> res = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() / 100 != 2) {
                throw new IOException("HTTP " + res.statusCode() + " from " + url);
            }
            byte[] body = res.body();
            if (res.headers().firstValue("Content-Encoding").map(v -> v.contains("gzip")).orElse(false)) {
                try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
                    body = in.readAllBytes();
                }
            }
            return body;
        } catch (IOException e) {
            // ConnectException and friends often carry no message at all.
            throw new RuntimeException(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted", e);
        }
    }

    /**
     * An art path, which is fetched as {@code BASE + path}: it must stay on the API's host, so
     * only a plain absolute path is taken ("@other.host/x" or "//x" would point elsewhere).
     */
    private static String path(String p) {
        return p != null && p.startsWith("/") && !p.startsWith("//") && p.indexOf('@') < 0 && p.indexOf('\\') < 0 ? p : null;
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? null : e.getAsString();
    }

    private static int num(JsonObject o, String key, int fallback) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        return (int) Math.round(e.getAsDouble());
    }

    private static boolean bool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }

    private static long epoch(String iso) {
        if (iso == null) {
            return 0L;
        }
        try {
            return Instant.parse(iso).toEpochMilli();
        } catch (RuntimeException e) {
            return 0L;
        }
    }
}
