package cc.serverlist.featured.friends;

import cc.serverlist.featured.compat.Compat;
import cc.serverlist.featured.FeaturedClient;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Who the player has been on a server with, for "Played with recently" in the Add box: anyone in
 * the tab list for ten minutes or more of one visit. Kept in {@code serverlist-cache/played-with.json}
 * and never sent anywhere; the Add box only asks serverlist.cc which of these names use Discovery.
 */
public final class PlayedWith {
    /** One person: where they were seen last, when, and for how long that visit. */
    public record Entry(UUID id, String name, String slug, String address, long lastAt, int minutes) {
    }

    private static final Path FILE = FabricLoader.getInstance().getGameDir().resolve("serverlist-cache").resolve("played-with.json");
    private static final int MIN_SECONDS = 600;
    private static final int MAX_ENTRIES = 100;
    private static final long KEEP_MS = 21L * 24 * 60 * 60_000;
    /** Enough for any real tab list; a bigger one is a lobby, not people played with. */
    private static final int MAX_LIST = 300;

    private static Map<UUID, Entry> entries;
    private static final Map<UUID, Integer> seconds = new HashMap<>();
    private static String visit;
    private static int ticks;
    private static boolean dirty;
    private static long savedAt;

    private PlayedWith() {
    }

    /** Once a second (every 20 ticks): a second more for everyone in the tab list. */
    static void tick(Minecraft mc) {
        if (++ticks < 20) {
            return;
        }
        ticks = 0;
        ClientPacketListener conn = mc.getConnection();
        ServerData sd = mc.getCurrentServer();
        if (conn == null || sd == null || mc.isLocalServer()) {
            visit = null;
            seconds.clear();
            save(false);
            return;
        }
        if (!sd.ip.equals(visit)) {
            visit = sd.ip;
            seconds.clear();
        }
        load();
        UUID me = mc.getUser().getProfileId();
        var players = conn.getListedOnlinePlayers();
        if (players.size() > MAX_LIST) {
            return;
        }
        String slug = null;
        boolean slugAsked = false;
        long now = System.currentTimeMillis();
        for (PlayerInfo p : players) {
            UUID id = Compat.profileId(p.getProfile());
            String name = Compat.profileName(p.getProfile());
            if (id == null || id.equals(me) || name == null || !Friends.NAME.matcher(name).matches()) {
                continue;
            }
            int s = seconds.merge(id, 1, Integer::sum);
            // From ten minutes on, the entry is kept up to date once a minute.
            if (s >= MIN_SECONDS && s % 60 == 0) {
                if (!slugAsked) {
                    slug = Friends.currentSlug(mc);
                    slugAsked = true;
                }
                entries.put(id, new Entry(id, name, slug, sd.ip, now, s / 60));
                dirty = true;
            }
        }
        save(false);
    }

    /** Everyone kept, the most recent first. */
    public static synchronized List<Entry> entries() {
        load();
        List<Entry> out = new ArrayList<>(entries.values());
        out.sort(Comparator.comparingLong(Entry::lastAt).reversed());
        return out;
    }

    private static synchronized void load() {
        if (entries != null) {
            return;
        }
        entries = new HashMap<>();
        if (!Files.isRegularFile(FILE)) {
            return;
        }
        try {
            JsonArray a = JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8)).getAsJsonArray();
            long cutoff = System.currentTimeMillis() - KEEP_MS;
            for (JsonElement el : a) {
                JsonObject o = el.getAsJsonObject();
                Entry e = new Entry(UUID.fromString(o.get("id").getAsString()), o.get("name").getAsString(),
                        o.has("slug") ? o.get("slug").getAsString() : null, o.get("address").getAsString(),
                        o.get("lastAt").getAsLong(), o.get("minutes").getAsInt());
                if (e.lastAt() >= cutoff) {
                    entries.put(e.id(), e);
                }
            }
        } catch (Exception e) {
            FeaturedClient.LOGGER.debug("Played-with list not read: {}", e.toString());
        }
    }

    /** Written at most once a minute while something changed, or straight away on leaving. */
    private static synchronized void save(boolean force) {
        long now = System.currentTimeMillis();
        if (!dirty || entries == null || (!force && visit != null && now - savedAt < 60_000)) {
            return;
        }
        dirty = false;
        savedAt = now;
        List<Entry> keep = new ArrayList<>(entries.values());
        keep.sort(Comparator.comparingLong(Entry::lastAt).reversed());
        if (keep.size() > MAX_ENTRIES) {
            for (Entry e : keep.subList(MAX_ENTRIES, keep.size())) {
                entries.remove(e.id());
            }
            keep = keep.subList(0, MAX_ENTRIES);
        }
        JsonArray a = new JsonArray();
        for (Entry e : keep) {
            JsonObject o = new JsonObject();
            o.addProperty("id", e.id().toString());
            o.addProperty("name", e.name());
            if (e.slug() != null) {
                o.addProperty("slug", e.slug());
            }
            o.addProperty("address", e.address());
            o.addProperty("lastAt", e.lastAt());
            o.addProperty("minutes", e.minutes());
            a.add(o);
        }
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, a.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            FeaturedClient.LOGGER.debug("Played-with list not written: {}", e.toString());
        }
    }
}
