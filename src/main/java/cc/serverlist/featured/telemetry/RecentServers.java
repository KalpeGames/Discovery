package cc.serverlist.featured.telemetry;

import cc.serverlist.featured.FeaturedClient;
import cc.serverlist.featured.api.ServerListApi;
import cc.serverlist.featured.api.TrackedServer;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Where the player has been: the servers they logged into, most recent first, kept in
 * {@code serverlist-cache/recent.json}. Purely local — it feeds the page's "Recently played"
 * row and is kept whether or not play stats are shared. Servers the tracker doesn't list are
 * remembered too; the row draws them from a live ping, with the address as the name.
 */
public final class RecentServers {
    private static final Path FILE = FabricLoader.getInstance().getGameDir().resolve("serverlist-cache").resolve("recent.json");
    private static final int MAX = 30;
    /** The slug prefix for a server the tracker doesn't know; never sent anywhere. */
    public static final String LOCAL = "local:";

    /** One server: how it was joined, what it called itself, when, and how much. */
    public static final class Entry {
        public final String address;
        public String slug;
        public String name;
        public long lastAt;
        public int sessions;
        public long seconds;

        Entry(String address) {
            this.address = address;
        }
    }

    private static final Object LOCK = new Object();
    private static List<Entry> entries;

    private RecentServers() {
    }

    /** Most recent first. A copy; the row is rebuilt from it on each recompute. */
    public static List<Entry> list() {
        synchronized (LOCK) {
            load();
            return new ArrayList<>(entries);
        }
    }

    /** A login worked: move (or add) the server to the front. */
    public static void joined(String address, String slug, String name) {
        String key = ServerListApi.normalizeAddress(address);
        if (key == null) {
            return;
        }
        synchronized (LOCK) {
            load();
            Entry e = null;
            for (Entry x : entries) {
                if (x.address.equals(key)) {
                    e = x;
                    break;
                }
            }
            if (e == null) {
                e = new Entry(key);
            }
            entries.remove(e);
            entries.add(0, e);
            if (slug != null) {
                e.slug = slug;
            }
            if (name != null && !name.isBlank() && !name.equalsIgnoreCase(key)) {
                e.name = name;
            }
            e.lastAt = System.currentTimeMillis();
            e.sessions++;
            while (entries.size() > MAX) {
                entries.remove(entries.size() - 1);
            }
            save();
        }
    }

    /** A session on the server ended after this many seconds. */
    public static void played(String address, long seconds) {
        String key = ServerListApi.normalizeAddress(address);
        if (key == null) {
            return;
        }
        synchronized (LOCK) {
            load();
            for (Entry x : entries) {
                if (x.address.equals(key)) {
                    x.seconds += Math.max(0, seconds);
                    save();
                    return;
                }
            }
        }
    }

    /** True for the stand-in record of a server the tracker doesn't list. */
    public static boolean isLocal(TrackedServer s) {
        return s.slug().startsWith(LOCAL);
    }

    /** The slug to report for a server, or null for one the tracker doesn't list. */
    public static String reportable(TrackedServer s) {
        return isLocal(s) ? null : s.slug();
    }

    /** A card's worth of record for a server the tracker doesn't list; the ping fills the rest. */
    public static TrackedServer standIn(Entry e) {
        return standIn(e.address, e.name);
    }

    /** As {@link #standIn(Entry)}, for any remembered address (the favourites use it too). */
    public static TrackedServer standIn(String address, String knownName) {
        String host = address;
        int port = 25565;
        int colon = host.lastIndexOf(':');
        if (colon > 0 && host.indexOf(':') == colon) {
            try {
                port = Integer.parseInt(host.substring(colon + 1));
                host = host.substring(0, colon);
            } catch (NumberFormatException ignored) {
                // an IPv6 literal or a typo: keep the whole thing as the host
            }
        }
        String name = knownName != null ? knownName : host;
        return new TrackedServer(LOCAL + address, name, host, port, address, true, 0, 0, null, "", null,
                0, -1, -1, 0, List.of(), 0, null, null, null, null, null, null, null, false);
    }

    // Must hold LOCK.
    private static void load() {
        if (entries != null) {
            return;
        }
        entries = new ArrayList<>();
        if (!Files.isRegularFile(FILE)) {
            return;
        }
        try {
            for (JsonElement el : JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8)).getAsJsonArray()) {
                JsonObject o = el.getAsJsonObject();
                String address = ServerListApi.normalizeAddress(o.get("address").getAsString());
                if (address == null) {
                    continue;
                }
                Entry e = new Entry(address);
                e.slug = o.has("slug") && !o.get("slug").isJsonNull() ? o.get("slug").getAsString() : null;
                e.name = o.has("name") && !o.get("name").isJsonNull() ? o.get("name").getAsString() : null;
                e.lastAt = o.has("lastAt") ? o.get("lastAt").getAsLong() : 0;
                e.sessions = o.has("sessions") ? o.get("sessions").getAsInt() : 0;
                e.seconds = o.has("seconds") ? o.get("seconds").getAsLong() : 0;
                entries.add(e);
            }
        } catch (Exception ex) {
            FeaturedClient.LOGGER.debug("recent.json not read: {}", ex.toString());
        }
    }

    // Must hold LOCK.
    private static void save() {
        JsonArray arr = new JsonArray();
        for (Entry e : entries) {
            JsonObject o = new JsonObject();
            o.addProperty("address", e.address);
            o.addProperty("slug", e.slug);
            o.addProperty("name", e.name);
            o.addProperty("lastAt", e.lastAt);
            o.addProperty("sessions", e.sessions);
            o.addProperty("seconds", e.seconds);
            arr.add(o);
        }
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, new GsonBuilder().setPrettyPrinting().create().toJson(arr), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            FeaturedClient.LOGGER.debug("recent.json not written: {}", ex.toString());
        }
    }

    /** "3 hours", "12 minutes" — the row's subtitle material. */
    public static String playtime(long seconds) {
        if (seconds >= 3600) {
            long h = seconds / 3600;
            return h + (h == 1 ? " hour" : " hours");
        }
        long m = Math.max(1, seconds / 60);
        return m + (m == 1 ? " minute" : " minutes");
    }

    /** "today", "yesterday", "3 days ago". */
    public static String ago(long at) {
        long days = (System.currentTimeMillis() - at) / 86_400_000L;
        if (days <= 0) {
            return "today";
        }
        if (days == 1) {
            return "yesterday";
        }
        return days + " days ago";
    }
}
