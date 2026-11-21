package cc.serverlist.featured.telemetry;

import cc.serverlist.featured.FeaturedClient;
import cc.serverlist.featured.api.ServerListApi;
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
 * The servers the player starred on the page, most recently starred first, kept in
 * {@code serverlist-cache/favourites.json}. Local only, like {@link RecentServers}; it feeds the
 * "Favourites" row under "Recently played".
 *
 * <p>Starring also puts the server in the multiplayer list, as the star always did. Each entry
 * remembers whether it was the star that put it there, so un-starring only takes out what the
 * star added and never a server the player had saved themselves.
 */
public final class Favourites {
    private static final Path FILE = FabricLoader.getInstance().getGameDir().resolve("serverlist-cache").resolve("favourites.json");
    private static final int MAX = 100;

    public static final class Entry {
        public final String address;
        public String slug;
        public String name;
        public long addedAt;
        /** True when starring added it to the multiplayer list, so un-starring may remove it. */
        public boolean addedToList;

        Entry(String address) {
            this.address = address;
        }
    }

    private static final Object LOCK = new Object();
    private static List<Entry> entries;

    private Favourites() {
    }

    /** Most recently starred first. A copy. */
    public static List<Entry> list() {
        synchronized (LOCK) {
            load();
            return new ArrayList<>(entries);
        }
    }

    public static boolean contains(String address) {
        String key = ServerListApi.normalizeAddress(address);
        if (key == null) {
            return false;
        }
        synchronized (LOCK) {
            load();
            return find(key) != null;
        }
    }

    /** Stars a server; puts it first if it already was. */
    public static void add(String address, String slug, String name, boolean addedToList) {
        String key = ServerListApi.normalizeAddress(address);
        if (key == null) {
            return;
        }
        synchronized (LOCK) {
            load();
            Entry e = find(key);
            if (e == null) {
                e = new Entry(key);
                e.addedToList = addedToList;
            }
            entries.remove(e);
            entries.add(0, e);
            if (slug != null && !slug.startsWith(RecentServers.LOCAL)) {
                e.slug = slug;
            }
            if (name != null && !name.isBlank() && !name.equalsIgnoreCase(key)) {
                e.name = name;
            }
            e.addedAt = System.currentTimeMillis();
            while (entries.size() > MAX) {
                entries.remove(entries.size() - 1);
            }
            save();
        }
    }

    /** Un-stars a server; returns what was removed, or null when it wasn't starred. */
    public static Entry remove(String address) {
        String key = ServerListApi.normalizeAddress(address);
        if (key == null) {
            return null;
        }
        synchronized (LOCK) {
            load();
            Entry e = find(key);
            if (e != null) {
                entries.remove(e);
                save();
            }
            return e;
        }
    }

    // Must hold LOCK.
    private static Entry find(String key) {
        for (Entry x : entries) {
            if (x.address.equals(key)) {
                return x;
            }
        }
        return null;
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
                e.addedAt = o.has("addedAt") ? o.get("addedAt").getAsLong() : 0;
                e.addedToList = o.has("addedToList") && o.get("addedToList").getAsBoolean();
                entries.add(e);
            }
        } catch (Exception ex) {
            FeaturedClient.LOGGER.debug("favourites.json not read: {}", ex.toString());
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
            o.addProperty("addedAt", e.addedAt);
            o.addProperty("addedToList", e.addedToList);
            arr.add(o);
        }
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, new GsonBuilder().setPrettyPrinting().create().toJson(arr), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            FeaturedClient.LOGGER.debug("favourites.json not written: {}", ex.toString());
        }
    }
}
