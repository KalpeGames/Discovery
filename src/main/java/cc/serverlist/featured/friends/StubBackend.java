package cc.serverlist.featured.friends;

import cc.serverlist.featured.FeaturedClient;
import cc.serverlist.featured.telemetry.TelemetryConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * A stand-in for serverlist.cc's friends, for trying the feature before the API has it: turned
 * on by {@code "friendsStub": true} in {@code config/serverlist.json}. Kept in
 * {@code serverlist-cache/friends-stub.json}; delete that file to start over.
 *
 * <p>It starts with a few friends and two requests. A request sent from here is accepted twenty
 * seconds later. Friends move between a handful of busy servers every ten minutes, so presence,
 * the badges and the toast all have something to show. Names containing "nobody" don't use
 * Discovery, to see that message.
 */
final class StubBackend implements Friends.Backend {
    private static final Path FILE = FabricLoader.getInstance().getGameDir().resolve("serverlist-cache").resolve("friends-stub.json");
    private static final String[] SERVERS = {"donutsmp", "hypixel", "minehut", "mcpvp", "pvphq", "hugosmp"};
    private static final long ACCEPT_AFTER_MS = 20_000;
    private static final long DAY = 24 * 60 * 60_000L;

    private record Entry(String name, long at) {
    }

    private final List<Entry> friends = new ArrayList<>();
    private final List<Entry> incoming = new ArrayList<>();
    private final List<Entry> outgoing = new ArrayList<>();
    private final List<Entry> blocked = new ArrayList<>();

    StubBackend() {
        load();
    }

    static UUID idOf(String name) {
        return UUID.nameUUIDFromBytes(("stub:" + name.toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public synchronized Friends.Snapshot fetch() {
        long now = System.currentTimeMillis();
        boolean changed = outgoing.removeIf(e -> {
            if (now - e.at() >= ACCEPT_AFTER_MS) {
                friends.add(new Entry(e.name(), now));
                return true;
            }
            return false;
        });
        if (changed) {
            save();
        }
        List<Friends.Friend> out = new ArrayList<>();
        long window = now / (10 * 60_000L);
        for (Entry e : friends) {
            int roll = Math.floorMod((e.name().toLowerCase(Locale.ROOT) + window).hashCode(), 10);
            boolean online = roll >= 3;
            String slug = roll >= 4 ? SERVERS[roll % SERVERS.length] : null;
            out.add(new Friends.Friend(idOf(e.name()), e.name(), online, slug));
        }
        return new Friends.Snapshot(true, null, out, people(incoming), people(outgoing), people(blocked));
    }

    @Override
    public synchronized Friends.Result request(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("nobody")) {
            return new Friends.Result(false, name + " doesn't use Discovery yet.");
        }
        if (find(blocked, n) != null) {
            return new Friends.Result(false, "You've blocked " + name + ". Unblock them in settings first.");
        }
        Entry asked = find(incoming, n);
        if (asked != null) {
            incoming.remove(asked);
            friends.add(new Entry(asked.name(), System.currentTimeMillis()));
            save();
            return new Friends.Result(true, "They'd asked you too. You're friends now.");
        }
        outgoing.add(new Entry(name, System.currentTimeMillis()));
        save();
        return new Friends.Result(true, "Sent to " + name + ".");
    }

    @Override
    public synchronized void act(String action, UUID id) {
        Entry e;
        switch (action) {
            case "accept" -> {
                if ((e = byId(incoming, id)) != null) {
                    incoming.remove(e);
                    friends.add(new Entry(e.name(), System.currentTimeMillis()));
                }
            }
            case "ignore" -> incoming.removeIf(x -> idOf(x.name()).equals(id));
            case "cancel" -> outgoing.removeIf(x -> idOf(x.name()).equals(id));
            case "remove" -> friends.removeIf(x -> idOf(x.name()).equals(id));
            case "block" -> {
                String name = null;
                for (List<Entry> list : List.of(friends, incoming, outgoing)) {
                    if ((e = byId(list, id)) != null) {
                        name = e.name();
                        list.remove(e);
                    }
                }
                if (name != null && byId(blocked, id) == null) {
                    blocked.add(new Entry(name, System.currentTimeMillis()));
                }
            }
            case "unblock" -> blocked.removeIf(x -> idOf(x.name()).equals(id));
            default -> {
            }
        }
        save();
    }

    @Override
    public void settings(TelemetryConfig c) {
        // Kept in the config file; nothing else to tell.
    }

    @Override
    public Set<String> usingDiscovery(List<String> names) {
        // Two in three, so the list shows it filters.
        Set<String> out = new HashSet<>();
        for (String n : names) {
            if (Math.floorMod(n.hashCode(), 3) != 0) {
                out.add(n);
            }
        }
        return out;
    }

    @Override
    public void presence(String slug) {
    }

    private static Entry find(List<Entry> list, String lowerName) {
        for (Entry e : list) {
            if (e.name().toLowerCase(Locale.ROOT).equals(lowerName)) {
                return e;
            }
        }
        return null;
    }

    private static Entry byId(List<Entry> list, UUID id) {
        for (Entry e : list) {
            if (idOf(e.name()).equals(id)) {
                return e;
            }
        }
        return null;
    }

    private static List<Friends.Person> people(List<Entry> list) {
        List<Friends.Person> out = new ArrayList<>();
        for (Entry e : list) {
            out.add(new Friends.Person(idOf(e.name()), e.name(), e.at()));
        }
        return out;
    }

    private void load() {
        if (!Files.isRegularFile(FILE)) {
            long now = System.currentTimeMillis();
            for (String n : new String[]{"Grian", "TommyInnit", "Tubbo", "Philza", "Skeppy", "Sapnap", "MumboJumbo", "Ranboo"}) {
                friends.add(new Entry(n, now - 5 * DAY));
            }
            incoming.add(new Entry("Quackity", now - 2 * DAY));
            incoming.add(new Entry("Purpled", now - 3 * 60 * 60_000L));
            save();
            return;
        }
        try {
            JsonObject o = JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8)).getAsJsonObject();
            read(o, "friends", friends);
            read(o, "incoming", incoming);
            read(o, "outgoing", outgoing);
            read(o, "blocked", blocked);
        } catch (Exception e) {
            FeaturedClient.LOGGER.debug("Friends stub not read: {}", e.toString());
        }
    }

    private static void read(JsonObject o, String key, List<Entry> into) {
        if (!o.has(key)) {
            return;
        }
        for (JsonElement el : o.getAsJsonArray(key)) {
            JsonObject e = el.getAsJsonObject();
            into.add(new Entry(e.get("name").getAsString(), e.get("at").getAsLong()));
        }
    }

    private void save() {
        JsonObject o = new JsonObject();
        o.add("friends", write(friends));
        o.add("incoming", write(incoming));
        o.add("outgoing", write(outgoing));
        o.add("blocked", write(blocked));
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, o.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            FeaturedClient.LOGGER.debug("Friends stub not written: {}", e.toString());
        }
    }

    private static JsonArray write(List<Entry> list) {
        JsonArray a = new JsonArray();
        for (Entry e : list) {
            JsonObject o = new JsonObject();
            o.addProperty("name", e.name());
            o.addProperty("at", e.at());
            a.add(o);
        }
        return a;
    }
}
