package cc.serverlist.featured.update;

import cc.serverlist.featured.FeaturedClient;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import net.minecraft.SharedConstants;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Updates the mod from its Modrinth page.
 *
 * <p>At launch it asks Modrinth once for the newest Fabric build of this project for the running
 * Minecraft version. If that build is newer than this one, the Discovery page offers it. The
 * player clicks, the jar is downloaded beside the game (not into {@code mods/}), checked against
 * the SHA-512 Modrinth publishes, and opened to make sure it really is this mod. It is installed
 * when the game closes.
 *
 * <p>It waits for the close because on Windows the running jar is locked, and because two
 * copies of a mod in {@code mods/} make Fabric refuse to start. {@link JarSwap} does the
 * replacing: in a shutdown hook where the OS allows it, otherwise from a small separate process
 * that waits for the game to exit first. At every step either the old jar or the new one is in
 * {@code mods/}, never both and never neither. If the swap can't finish, the download stays
 * staged and the next launch tries again.
 */
public final class Updater {
    public enum State {
        /** Before the check has run. */
        IDLE,
        CHECKING,
        /** Up to date, or Modrinth had nothing for this game version. */
        NONE,
        AVAILABLE,
        DOWNLOADING,
        /** Downloaded and verified; installs when the game closes. */
        READY,
        FAILED,
        /** Not running from a jar (a dev run, or bundled inside another mod), so nothing to replace. */
        UNSUPPORTED
    }

    /**
     * Discovery's permanent Modrinth project id, once known; empty until then. With it set, the
     * slug is never consulted: a slug can be renamed, freed and taken by someone else, and
     * whoever holds it would be offering every player a jar.
     */
    static final String PROJECT_ID = "";
    /** The slug the id is learned from while {@link #PROJECT_ID} is empty. */
    private static final String SLUG = "discovery";
    private static final String PROJECT = env("SERVERLIST_MODRINTH_PROJECT", PROJECT_ID.isEmpty() ? SLUG : PROJECT_ID);
    /**
     * The project id this install first saw behind the slug, kept outside the update folder
     * (which is cleared). Every later check must find the same id, so if the slug ever comes to
     * mean another project, this install stops offering updates instead of offering its jar.
     */
    private static final Path PINNED = FabricLoader.getInstance().getConfigDir().resolve("serverlist-modrinth-project.txt");
    private static final Pattern PROJECT_ID_SHAPE = Pattern.compile("[A-Za-z0-9]{8}");
    /** Modrinth's API root; {@code SERVERLIST_MODRINTH_API} points a test at a local stand-in. */
    private static final String API = env("SERVERLIST_MODRINTH_API", "https://api.modrinth.com");
    /** The only host a download may come from, unless the API itself was pointed elsewhere. */
    private static final String FILE_HOST = API.equals("https://api.modrinth.com") ? "cdn.modrinth.com" : URI.create(API).getHost();
    /** Far above any real build of this mod; stops a bad listing filling the disk. */
    private static final long MAX_BYTES = 64L << 20;
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._+-]{1,120}\\.jar");

    /** Where a download waits for the game to close. Outside {@code mods/}, so Fabric never sees it. */
    static final Path DIR = FabricLoader.getInstance().getGameDir().resolve("serverlist-update");
    private static final Path PENDING = DIR.resolve("pending.json");
    static final Path LOG = DIR.resolve("update.log");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "serverlist-update");
        t.setDaemon(true);
        return t;
    });

    private static volatile State state = State.IDLE;
    private static volatile String offered;
    private static volatile String error;
    private static volatile int progress;
    private static volatile Candidate candidate;
    /** The jar this copy of the mod was loaded from; null when there isn't one. */
    private static Path installed;
    private static boolean hookAdded;

    /** One downloadable build, as Modrinth lists it. */
    record Candidate(String version, String url, String filename, String sha512, long size) {
    }

    private Updater() {
    }

    public static State state() {
        return state;
    }

    /** The version on offer, without its build suffix: "0.2.0" rather than "0.2.0+mc26.2". */
    public static String offeredVersion() {
        String v = offered;
        if (v == null) {
            return "";
        }
        int plus = v.indexOf('+');
        return plus > 0 ? v.substring(0, plus) : v;
    }

    public static int progress() {
        return progress;
    }

    public static String error() {
        return error;
    }

    /** Called once at launch: finish or resume an earlier update, then ask Modrinth. */
    public static void start() {
        WORKER.execute(() -> {
            try {
                check();
            } catch (Exception e) {
                // Being offline or Modrinth being down is not worth a word on screen.
                FeaturedClient.LOGGER.info("Update check failed: {}", e.toString());
                state = State.NONE;
            }
        });
    }

    /** The button: fetch the offered build (or retry a failed fetch). */
    public static void download() {
        State s = state;
        if (candidate == null || (s != State.AVAILABLE && s != State.FAILED)) {
            return;
        }
        state = State.DOWNLOADING;
        progress = 0;
        error = null;
        WORKER.execute(() -> {
            try {
                fetch(candidate);
                state = State.READY;
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                FeaturedClient.LOGGER.warn("Update download failed: {}", error);
                state = State.FAILED;
            }
        });
    }

    // ------------------------------------------------------------------
    // Checking
    // ------------------------------------------------------------------

    private static void check() throws IOException, InterruptedException {
        state = State.CHECKING;
        installed = locateJar();
        if (installed == null) {
            FeaturedClient.LOGGER.info("Self-update off: not running from a jar");
            state = State.UNSUPPORTED;
            return;
        }
        Version current = currentVersion();
        if (resumePending(current)) {
            return;
        }

        String projectId = projectId();
        if (projectId == null) {
            state = State.NONE;
            return;
        }

        String game = SharedConstants.getCurrentVersion().name();
        String url = API + "/v2/project/" + enc(projectId) + "/version"
                + "?loaders=" + enc("[\"fabric\"]")
                + "&game_versions=" + enc("[\"" + game + "\"]");
        HttpResponse<String> res = HTTP.send(request(url).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() == 404) {
            FeaturedClient.LOGGER.info("Update check: no Modrinth project '{}'", PROJECT);
            state = State.NONE;
            return;
        }
        if (res.statusCode() != 200) {
            throw new IOException("HTTP " + res.statusCode() + " from Modrinth");
        }

        Candidate best = newest(JsonParser.parseString(res.body()).getAsJsonArray(), current, projectId);
        if (best == null) {
            FeaturedClient.LOGGER.info("Discovery is up to date ({} for {})", current.getFriendlyString(), game);
            state = State.NONE;
            return;
        }
        candidate = best;
        offered = best.version();
        FeaturedClient.LOGGER.info("Discovery {} is available (running {})", best.version(), current.getFriendlyString());
        state = State.AVAILABLE;
    }

    /**
     * The project to take versions from, or null when there is none to trust. A built-in id is
     * used as is. Otherwise the slug is looked up, and its id must match the one this install
     * pinned the first time (pinning it now if this is the first time).
     */
    private static String projectId() throws IOException, InterruptedException {
        if (!PROJECT_ID.isEmpty() && PROJECT.equals(PROJECT_ID)) {
            return PROJECT_ID;
        }
        HttpResponse<String> res = HTTP.send(request(API + "/v2/project/" + enc(PROJECT)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() == 404) {
            FeaturedClient.LOGGER.info("Update check: no Modrinth project '{}'", PROJECT);
            return null;
        }
        if (res.statusCode() != 200) {
            throw new IOException("HTTP " + res.statusCode() + " from Modrinth");
        }
        String id = str(JsonParser.parseString(res.body()).getAsJsonObject(), "id");
        if (id == null || !PROJECT_ID_SHAPE.matcher(id).matches()) {
            FeaturedClient.LOGGER.warn("Update check: Modrinth gave no usable id for '{}'", PROJECT);
            return null;
        }
        String pinned = Files.isRegularFile(PINNED) ? Files.readString(PINNED).trim() : "";
        if (pinned.isEmpty()) {
            Files.createDirectories(PINNED.getParent());
            Files.writeString(PINNED, id);
            return id;
        }
        if (!pinned.equals(id)) {
            FeaturedClient.LOGGER.warn("Update check: '{}' is now Modrinth project {}, not {} as before; not updating from it",
                    PROJECT, id, pinned);
            return null;
        }
        return id;
    }

    /**
     * The newest release or beta that is newer than {@code current} and has a jar we can take.
     * Alphas are skipped: those are for people who asked for them, not something to push.
     */
    static Candidate newest(JsonArray versions, Version current, String projectId) {
        Candidate best = null;
        Version bestVersion = null;
        for (JsonElement el : versions) {
            JsonObject v = el.getAsJsonObject();
            if (!projectId.equals(str(v, "project_id"))) {
                continue;
            }
            String type = str(v, "version_type");
            if (!"release".equals(type) && !"beta".equals(type)) {
                continue;
            }
            String number = str(v, "version_number");
            Version parsed;
            try {
                parsed = Version.parse(number);
            } catch (VersionParsingException e) {
                continue;
            }
            if (parsed.compareTo(current) <= 0 || (bestVersion != null && parsed.compareTo(bestVersion) <= 0)) {
                continue;
            }
            Candidate c = fileOf(v, number);
            if (c != null) {
                best = c;
                bestVersion = parsed;
            }
        }
        return best;
    }

    /** The version's primary jar, if it is one we are willing to download. */
    private static Candidate fileOf(JsonObject version, String number) {
        JsonArray files = version.getAsJsonArray("files");
        if (files == null || files.isEmpty()) {
            return null;
        }
        JsonObject pick = null;
        for (JsonElement f : files) {
            JsonObject o = f.getAsJsonObject();
            if (o.has("primary") && o.get("primary").getAsBoolean()) {
                pick = o;
                break;
            }
        }
        if (pick == null) {
            pick = files.get(0).getAsJsonObject();
        }
        String url = str(pick, "url");
        String name = str(pick, "filename");
        JsonObject hashes = pick.getAsJsonObject("hashes");
        String sha512 = hashes == null ? null : str(hashes, "sha512");
        long size = pick.has("size") ? pick.get("size").getAsLong() : 0;
        if (url == null || name == null || sha512 == null || !SAFE_NAME.matcher(name).matches()
                || size <= 0 || size > MAX_BYTES) {
            return null;
        }
        URI uri = URI.create(url);
        boolean realCdn = FILE_HOST.equals("cdn.modrinth.com");
        if (!FILE_HOST.equalsIgnoreCase(uri.getHost()) || (realCdn && !"https".equals(uri.getScheme()))) {
            return null;
        }
        return new Candidate(number, url, name, sha512.toLowerCase(Locale.ROOT), size);
    }

    // ------------------------------------------------------------------
    // Downloading
    // ------------------------------------------------------------------

    private static void fetch(Candidate c) throws IOException, InterruptedException {
        Files.createDirectories(DIR);
        Path part = DIR.resolve(c.filename() + ".part");
        Path staged = DIR.resolve(c.filename());

        HttpResponse<InputStream> res = HTTP.send(request(c.url()).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() != 200) {
            res.body().close();
            throw new IOException("HTTP " + res.statusCode() + " downloading " + c.filename());
        }
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-512");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        long read = 0;
        try (InputStream in = res.body(); OutputStream out = Files.newOutputStream(part)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                read += n;
                if (read > MAX_BYTES) {
                    throw new IOException("download is larger than Modrinth said");
                }
                sha.update(buf, 0, n);
                out.write(buf, 0, n);
                progress = (int) Math.min(99, read * 100 / c.size());
            }
        } catch (IOException e) {
            Files.deleteIfExists(part);
            throw e;
        }
        String got = HexFormat.of().formatHex(sha.digest());
        if (!got.equals(c.sha512())) {
            Files.deleteIfExists(part);
            throw new IOException("the download didn't match Modrinth's checksum");
        }
        if (!isThisMod(part)) {
            Files.deleteIfExists(part);
            throw new IOException("the download isn't Discovery for this Minecraft version");
        }
        Files.move(part, staged, StandardCopyOption.REPLACE_EXISTING);

        JsonObject pending = new JsonObject();
        pending.addProperty("version", c.version());
        pending.addProperty("file", c.filename());
        pending.addProperty("sha512", c.sha512());
        Files.writeString(PENDING, pending.toString());
        progress = 100;
        JarSwap.log(LOG, "Downloaded " + c.filename() + " (" + read + " bytes), installs when the game closes");
        FeaturedClient.LOGGER.info("Discovery {} downloaded; it installs when the game closes", c.version());
        armSwap(staged);
    }

    /** Opens the jar and reads its fabric.mod.json: a different mod must never replace this one. */
    private static boolean isThisMod(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry("fabric.mod.json");
            if (entry == null) {
                return false;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                JsonObject meta = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                return FeaturedClient.MOD_ID.equals(str(meta, "id")) && fitsThisGame(meta);
            }
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Whether the jar's own minecraft range takes the running game. A Modrinth version tagged
     * with a game version its jar doesn't support would otherwise be installed, and Fabric would
     * then refuse to start. A jar that names no range is taken.
     */
    private static boolean fitsThisGame(JsonObject meta) throws VersionParsingException {
        JsonObject depends = meta.getAsJsonObject("depends");
        JsonElement range = depends == null ? null : depends.get("minecraft");
        if (range == null || range.isJsonNull()) {
            return true;
        }
        Version game = FabricLoader.getInstance().getModContainer("minecraft").orElseThrow().getMetadata().getVersion();
        if (range.isJsonArray()) {
            for (JsonElement r : range.getAsJsonArray()) {
                if (VersionPredicate.parse(r.getAsString()).test(game)) {
                    return true;
                }
            }
            return false;
        }
        return VersionPredicate.parse(range.getAsString()).test(game);
    }

    // ------------------------------------------------------------------
    // Installing
    // ------------------------------------------------------------------

    /**
     * A download from an earlier session. If this is already the version it held, the swap
     * worked and the leftovers go. If not, the swap never ran, so arm it again for this close.
     */
    private static boolean resumePending(Version current) {
        if (!Files.isRegularFile(PENDING)) {
            return false;
        }
        try {
            JsonObject p = JsonParser.parseString(Files.readString(PENDING)).getAsJsonObject();
            String version = str(p, "version");
            String file = str(p, "file");
            String sha512 = str(p, "sha512");
            if (version != null && Version.parse(version).compareTo(current) <= 0) {
                FeaturedClient.LOGGER.info("Discovery updated to {}", current.getFriendlyString());
                JarSwap.log(LOG, "Running " + current.getFriendlyString() + ", update finished");
                clearStaging();
                return false;
            }
            Path staged = file == null || !SAFE_NAME.matcher(file).matches() ? null : DIR.resolve(file);
            if (staged != null && Files.isRegularFile(staged) && sha512 != null && sha512.equals(sha512Of(staged)) && isThisMod(staged)) {
                offered = version;
                progress = 100;
                armSwap(staged);
                FeaturedClient.LOGGER.info("Discovery {} is still waiting to install; it will on this close", version);
                state = State.READY;
                return true;
            }
        } catch (Exception e) {
            FeaturedClient.LOGGER.info("Dropping an unreadable pending update: {}", e.toString());
        }
        clearStaging();
        return false;
    }

    /** Swap the jar as the game shuts down. Added once, whichever path armed it. */
    private static synchronized void armSwap(Path staged) {
        if (hookAdded) {
            return;
        }
        // The new jar's name in mods/ is ours, not the download's, so a listing can never name
        // (and so replace) some other mod's file. If that name is taken by anything but this
        // jar, the old name is kept.
        String version = offered == null ? "update" : offered.replaceAll("[^A-Za-z0-9._+-]", "_");
        Path target = installed.resolveSibling("serverlist-discovery-" + version + ".jar");
        if (Files.exists(target) && !target.equals(installed)) {
            target = installed;
        }
        final Path finalTarget = target;
        hookAdded = true;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (JarSwap.trySwap(installed, staged, finalTarget, LOG, true)) {
                return;
            }
            // Locked (Windows holds a running jar open): a separate process waits for the
            // game to exit, then does the same thing.
            try {
                spawnHelper(staged, finalTarget);
            } catch (IOException e) {
                JarSwap.log(LOG, "Couldn't start the installer: " + e + "; will retry next launch");
            }
        }, "serverlist-update-swap"));
    }

    /**
     * Runs {@link JarSwap} as its own Java process. Its class is copied out of this jar first,
     * because this jar is the file it is about to delete.
     */
    private static void spawnHelper(Path staged, Path target) throws IOException {
        Path cp = DIR.resolve("swap");
        String classFile = JarSwap.class.getName().replace('.', '/') + ".class";
        Path out = cp.resolve(classFile);
        Files.createDirectories(out.getParent());
        try (InputStream in = JarSwap.class.getClassLoader().getResourceAsStream(classFile)) {
            if (in == null) {
                throw new IOException("installer class missing from the jar");
            }
            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
        }
        String java = ProcessHandle.current().info().command().orElseGet(() -> {
            boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
            return Path.of(System.getProperty("java.home"), "bin", windows ? "javaw.exe" : "java").toString();
        });
        List<String> cmd = List.of(java, "-Xmx32m", "-cp", cp.toString(), JarSwap.class.getName(),
                String.valueOf(ProcessHandle.current().pid()), installed.toString(), staged.toString(), target.toString(), LOG.toString());
        new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(LOG.resolveSibling("installer-output.log").toFile()))
                .start();
        JarSwap.log(LOG, "Jar is locked; handed the swap to an installer process");
    }

    private static void clearStaging() {
        try (var files = Files.list(DIR)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                String name = f.getFileName().toString();
                // Keep the logs; they are how a failed install gets diagnosed.
                if (name.endsWith(".log")) {
                    continue;
                }
                if (Files.isDirectory(f)) {
                    try (var inner = Files.walk(f)) {
                        inner.sorted(java.util.Comparator.reverseOrder()).forEach(q -> q.toFile().delete());
                    }
                } else {
                    Files.deleteIfExists(f);
                }
            }
        } catch (IOException e) {
            // Nothing there, or nothing that matters.
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * The jar this mod was loaded from, or null for a dev run (classes on disk) or a copy nested
     * inside another mod's jar. {@code SERVERLIST_UPDATE_JAR} names one for testing.
     */
    private static Path locateJar() {
        String forced = FeaturedClient.devEnv("SERVERLIST_UPDATE_JAR");
        if (forced != null && !forced.isBlank()) {
            Path p = Path.of(forced);
            return Files.isRegularFile(p) ? p.toAbsolutePath() : null;
        }
        ModContainer mod = FabricLoader.getInstance().getModContainer(FeaturedClient.MOD_ID).orElse(null);
        if (mod == null || mod.getOrigin().getKind() != ModOrigin.Kind.PATH) {
            return null;
        }
        List<Path> paths = mod.getOrigin().getPaths();
        if (paths.size() != 1) {
            return null;
        }
        Path p = paths.get(0);
        return Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar") ? p.toAbsolutePath() : null;
    }

    private static Version currentVersion() {
        return FabricLoader.getInstance().getModContainer(FeaturedClient.MOD_ID)
                .map(m -> m.getMetadata().getVersion())
                .orElseThrow();
    }

    private static HttpRequest.Builder request(String url) {
        // Modrinth asks every client to say who it is, with a way to reach the author.
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "serverlist-cc/discovery/" + currentVersion().getFriendlyString() + " (https://serverlist.cc)");
    }

    private static String sha512Of(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest sha = MessageDigest.getInstance("SHA-512");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                sha.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String env(String key, String fallback) {
        String v = FeaturedClient.devEnv(key);
        return v == null ? fallback : v;
    }
}
