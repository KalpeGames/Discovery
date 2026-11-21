package cc.serverlist.featured.ui;

import cc.serverlist.featured.api.TrackedServer;
import cc.serverlist.featured.telemetry.TelemetryConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sort &amp; filter: how the full server list is ordered and cut when the player browses it
 * (the Sort &amp; filter tab of {@code SettingsScreen}). The choices live in {@code config/serverlist.json}; anything but
 * the defaults turns the page from strips into one grid over every server, sorted and filtered
 * here, with the search box narrowing it further.
 *
 * <p>Versions come from the tracker's version string, which is whatever the server puts in
 * its status reply: "Paper 1.21.4", "Velocity 1.7.2-26.3", "Requires MC 1.8 / 1.21", or a
 * slogan with no version at all. Every version-looking number in it is read; the lowest and
 * highest are the server's range. One that names several versions or a proxy is taken to be
 * a network that lets newer clients in too (they nearly all run ViaVersion), so it "supports"
 * anything from its lowest version up. One naming a single version supports that version's
 * family (1.21.4 is a 1.21 server). A server whose string names nothing sorts last and is
 * left out by any version filter.
 */
public final class Browse {
    private Browse() {
    }

    public enum Sort {
        TOP("top", "Top (recommended)"),
        PLAYERS("players", "Most players"),
        FEWEST("fewest", "Fewest players"),
        VERSION_NEW("version_new", "Newest version"),
        VERSION_OLD("version_old", "Oldest version"),
        RISING("rising", "Growing this week"),
        ADDED("added", "Newest on the list"),
        NAME("name", "Name (A-Z)");

        public final String id;
        public final String label;

        Sort(String id, String label) {
            this.id = id;
            this.label = label;
        }

        public static Sort of(String id) {
            for (Sort s : values()) {
                if (s.id.equals(id)) {
                    return s;
                }
            }
            return TOP;
        }
    }

    public enum Version {
        ANY("any", "Any", -1),
        MINE("mine", "Mine (" + gameVersion() + ")", -1),
        V26("26", "26.x", key(26, 0, 0)),
        V1_21("1.21", "1.21", key(1, 21, 0)),
        V1_20("1.20", "1.20", key(1, 20, 0)),
        V1_16("1.16", "1.16", key(1, 16, 0)),
        V1_12("1.12", "1.12", key(1, 12, 0)),
        V1_8("1.8", "1.8 (PvP)", key(1, 8, 0));

        public final String id;
        public final String label;
        /** The family's own key, major and minor with no patch; -1 for the two that aren't one. */
        final int family;

        Version(String id, String label, int family) {
            this.id = id;
            this.label = label;
            this.family = family;
        }

        public static Version of(String id) {
            for (Version v : values()) {
                if (v.id.equals(id)) {
                    return v;
                }
            }
            return ANY;
        }
    }

    /** 1.x versions, and the year-numbered ones from 26.1 on (26.2, 27.1, ...). */
    private static final Pattern VERSION = Pattern.compile("(?<![\\d.])(1|2[0-9]|3[0-9])\\.(\\d{1,2})(?:\\.(\\d{1,2}))?(?![\\d])");
    private static final Pattern MULTI = Pattern.compile("velocity|bungee|waterfall|travertine|flamecord|requires|\\bvia|\\d(?:\\.x)?\\s*\\+", Pattern.CASE_INSENSITIVE);

    /** major.minor.patch as one sortable number: 1.21.4 is 12104, 26.2 is 260200. */
    static int key(int major, int minor, int patch) {
        return major * 10000 + minor * 100 + patch;
    }

    private static String gameVersion;

    /** The running game's version, "26.2", from Fabric's view of the minecraft mod. */
    static String gameVersion() {
        if (gameVersion == null) {
            gameVersion = FabricLoader.getInstance().getModContainer("minecraft")
                    .map(m -> m.getMetadata().getVersion().getFriendlyString())
                    .orElse("26.2");
        }
        return gameVersion;
    }

    private static int gameMajor = -1;

    /** The running game's year (26 for 26.2); never below 26. */
    static int gameMajor() {
        if (gameMajor < 0) {
            int m = 26;
            try {
                m = Math.max(26, Integer.parseInt(gameVersion().split("\\.")[0]));
            } catch (NumberFormatException ignored) {
                // a snapshot name: keep 26
            }
            gameMajor = m;
        }
        return gameMajor;
    }

    /** A server's version range as {@code [min, max, multi]}, or null when its string names none. */
    static int[] range(String version) {
        if (version == null || version.isEmpty()) {
            return null;
        }
        Matcher m = VERSION.matcher(version);
        int min = Integer.MAX_VALUE;
        int max = -1;
        int count = 0;
        while (m.find()) {
            int major = Integer.parseInt(m.group(1));
            int minor = Integer.parseInt(m.group(2));
            int patch = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
            if (major == 1 && minor > 40) {
                continue; // "1.50x boost" and the like
            }
            // Year-numbered versions began at 26.1 and come a few a year, so anything before
            // 26, past next year, or with a minor in double figures is a date ("26.10 promo").
            if (major > 1 && (major < 26 || major > gameMajor() + 1 || minor > 9)) {
                continue;
            }
            int k = key(major, minor, patch);
            min = Math.min(min, k);
            max = Math.max(max, k);
            count++;
        }
        if (count == 0) {
            return null;
        }
        boolean multi = count > 1 || MULTI.matcher(version).find();
        return new int[]{min, max, multi ? 1 : 0};
    }

    /**
     * A version's family: major.minor for 1.x (1.21.4 is in 1.21), the year alone for the
     * year-numbered ones (26.2 is in 26.x), whose drops are as small as old patch releases.
     */
    static int family(int key) {
        return key >= key(20, 0, 0) ? key / 10000 * 10000 : key / 100 * 100;
    }

    /** Whether a server whose version string says this lets a client of {@code family} in. */
    static boolean supports(int[] r, int family) {
        if (r == null) {
            return false;
        }
        int familyOfMin = family(r[0]);
        if (r[2] == 1) {
            return family >= familyOfMin;
        }
        return familyOfMin == family;
    }

    /** True when the choices differ from the plain strips: the page becomes the browsing grid. */
    public static boolean active(TelemetryConfig c) {
        return Sort.of(c.sort) != Sort.TOP || Version.of(c.version) != Version.ANY || c.hideFull || c.hideEmpty;
    }

    /** Whether versions are what the grid is ordered or cut by, so each card should show its own. */
    public static boolean byVersion(TelemetryConfig c) {
        Sort s = Sort.of(c.sort);
        return s == Sort.VERSION_NEW || s == Sort.VERSION_OLD || Version.of(c.version) != Version.ANY;
    }

    /** Back to the defaults; cracked servers are a preference rather than a filter and stay as they are. */
    public static void reset(TelemetryConfig c) {
        c.sort = Sort.TOP.id;
        c.version = Version.ANY.id;
        c.hideFull = false;
        c.hideEmpty = false;
    }

    /** Whether a server passes the filters (not the search text; the page matches that itself). */
    public static boolean keep(TrackedServer s, TelemetryConfig c) {
        if (s.cracked() && !c.showCracked) {
            return false;
        }
        if (c.hideFull && s.maxPlayers() > 0 && s.players() >= s.maxPlayers()) {
            return false;
        }
        if (c.hideEmpty && s.players() <= 0) {
            return false;
        }
        Version v = Version.of(c.version);
        if (v == Version.ANY) {
            return true;
        }
        int[] r = range(s.version());
        if (v == Version.MINE) {
            int[] mine = range(gameVersion());
            return mine != null && supports(r, family(mine[0]));
        }
        return supports(r, v.family);
    }

    /** The servers in the chosen order. The input is most players first, which breaks ties. */
    public static List<TrackedServer> sort(List<TrackedServer> in, TelemetryConfig c) {
        List<TrackedServer> out = new ArrayList<>(in);
        Comparator<TrackedServer> byPlayers = Comparator.comparingInt(TrackedServer::players).reversed();
        switch (Sort.of(c.sort)) {
            case FEWEST -> out.sort(Comparator.comparingInt(TrackedServer::players));
            case VERSION_NEW -> out.sort(Comparator.comparingInt((TrackedServer s) -> {
                int[] r = range(s.version());
                return r == null ? Integer.MAX_VALUE : -r[1];
            }).thenComparing(byPlayers));
            case VERSION_OLD -> out.sort(Comparator.comparingInt((TrackedServer s) -> {
                int[] r = range(s.version());
                return r == null ? Integer.MAX_VALUE : r[0];
            }).thenComparing(byPlayers));
            case RISING -> out.sort(Comparator.comparingDouble((TrackedServer s) -> -growth(s)).thenComparing(byPlayers));
            case ADDED -> out.sort(Comparator.comparingLong(TrackedServer::addedAt).reversed().thenComparing(byPlayers));
            case NAME -> out.sort(Comparator.comparing((TrackedServer s) -> s.name().toLowerCase(Locale.ROOT)));
            case TOP -> {
                // Worked out once per server, not once per comparison.
                java.util.Map<TrackedServer, Double> score = new java.util.IdentityHashMap<>();
                for (TrackedServer s : out) {
                    score.put(s, topScore(s));
                }
                out.sort(Comparator.comparingDouble((TrackedServer s) -> -score.get(s)).thenComparing(byPlayers));
            }
            default -> out.sort(byPlayers);
        }
        return out;
    }

    /**
     * Now against the same hour last week, as the API's Rising row reckons it, but only for
     * servers with enough players for the ratio to mean something: a server going from 2 to 6
     * is not a trend. Those, and servers with no week-old figure, come after every grower.
     */
    private static double growth(TrackedServer s) {
        if (s.weekAgo() < 0 || s.players() < 20) {
            return -1e9;
        }
        return (double) s.players() / Math.max(s.weekAgo(), 10) - 1.0;
    }

    /**
     * What Top servers is ordered by: not the raw count alone, which would put the same few
     * giant networks first forever, but the count with its size damped and the things a player
     * cares about added in.
     * <ul>
     *   <li>Size, as a logarithm: 10,000 online is one point over 1,000, not ten times it.</li>
     *   <li>Momentum: now against this hour last week and yesterday, so a server on the way up
     *       climbs and one emptying out slips. Capped, so one good evening is not a takeover.</li>
     *   <li>How near its own record it is: busy for itself right now.</li>
     *   <li>Whether the player can get in: one that takes this game's version is lifted, one that
     *       plainly doesn't is pushed down, and a full one loses a little.</li>
     *   <li>A complete listing (art, a description) and being new on the list, a little each.</li>
     * </ul>
     * Servers with a handful of players don't get the momentum or record bonuses at all; 3 to 9
     * is not a trend. Offline servers sink to the bottom.
     */
    static double topScore(TrackedServer s) {
        if (!s.online()) {
            return -100;
        }
        int p = Math.max(0, s.players());
        double score = Math.log10(p + 1);
        if (p >= 25) {
            if (s.weekAgo() >= 0) {
                score += 0.8 * clamp(Math.log((p + 25.0) / (s.weekAgo() + 25.0)), -0.6, 0.6);
            }
            if (s.dayAgo() >= 0) {
                score += 0.4 * clamp(Math.log((p + 25.0) / (s.dayAgo() + 25.0)), -0.5, 0.5);
            }
            if (s.peakPlayers() > 0) {
                score += 0.3 * Math.min(1.0, (double) p / s.peakPlayers());
            }
        }
        int[] mine = range(gameVersion());
        int[] r = range(s.version());
        if (mine != null && r != null) {
            score += supports(r, family(mine[0])) ? 0.25 : -0.5;
        }
        if (s.maxPlayers() > 0 && p >= s.maxPlayers()) {
            score -= 0.3;
        }
        if (s.bannerCard() != null || s.bannerWide() != null) {
            score += 0.2;
        }
        if (s.description() != null && !s.description().isBlank()) {
            score += 0.05;
        }
        long age = System.currentTimeMillis() - s.addedAt();
        long month = 30L * 24 * 3600 * 1000;
        if (s.addedAt() > 0 && age >= 0 && age < month) {
            score += 0.2 * (1.0 - (double) age / month);
        }
        return score;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    /** The line above the grid: what is being shown, in a few words. */
    public static String summary(TelemetryConfig c) {
        List<String> parts = new ArrayList<>();
        parts.add(Sort.of(c.sort).label);
        Version v = Version.of(c.version);
        if (v != Version.ANY) {
            parts.add(v == Version.MINE ? "joins on " + gameVersion() : "plays " + v.label);
        }
        if (c.hideFull) {
            parts.add("not full");
        }
        if (c.hideEmpty) {
            parts.add("not empty");
        }
        if (c.showCracked) {
            parts.add("cracked shown");
        }
        return String.join(" · ", parts);
    }
}
