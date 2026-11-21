package cc.serverlist.featured.friends;

import cc.serverlist.featured.compat.Compat;
import cc.serverlist.featured.FeaturedClient;
import cc.serverlist.featured.api.Pacing;
import cc.serverlist.featured.api.ServerListApi;
import cc.serverlist.featured.telemetry.Telemetry;
import cc.serverlist.featured.telemetry.TelemetryConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Friends: who they are, where they play, and the requests between. Lives on the title screen
 * and the Discovery page; the only thing it ever does in game is the opt-in toast when a friend
 * joins the server you're on.
 *
 * <p>Identity is the same Mojang-proven token play stats use, so there's no account to make.
 * Presence comes from what serverlist.cc already knows: the server, never the world or where
 * in it. Everything goes through one background thread; the page reads {@link #snapshot()}
 * each frame.
 *
 * <p>Until serverlist.cc serves {@code /v1/friends}, {@code "friendsStub": true} in
 * {@code config/serverlist.json} swaps in {@link StubBackend}, a local stand-in for testing.
 */
public final class Friends {
    /** A friend. {@code on} is a server slug, or null; {@code online} without {@code on} is an unlisted server. */
    public record Friend(UUID id, String name, boolean online, String on) {
        public boolean unlisted() {
            return online && on == null;
        }
    }

    /** A request either way, or a blocked player: who, and when it happened. */
    public record Person(UUID id, String name, long at) {
    }

    /** Everything the page shows. {@code unavailable} says why friends can't be used right now, or is null. */
    public record Snapshot(boolean loaded, String unavailable, List<Friend> friends, List<Person> incoming,
                           List<Person> outgoing, List<Person> blocked) {
        static final Snapshot EMPTY = new Snapshot(false, null, List.of(), List.of(), List.of(), List.of());
    }

    /** What a request came to, worded for the line under the name field. */
    public record Result(boolean ok, String message) {
    }

    /** The other end: serverlist.cc, or the local stand-in. Every call blocks; the friends thread makes them. */
    interface Backend {
        Snapshot fetch() throws IOException;

        Result request(String name) throws IOException;

        /** accept, ignore, cancel, remove, block or unblock. */
        void act(String action, UUID id) throws IOException;

        void settings(TelemetryConfig c) throws IOException;

        /** Which of these names (lower case) use Discovery. */
        Set<String> usingDiscovery(List<String> names) throws IOException;

        /** Where the player is, for when play stats are off and nothing else says so. */
        void presence(String slug) throws IOException;
    }

    static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    // How often to refresh and report presence is Pacing's: the API can stretch or pause it.

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "serverlist-friends");
        t.setDaemon(true);
        return t;
    });

    private static volatile Snapshot snapshot = Snapshot.EMPTY;
    private static Backend backend;
    private static boolean backendStub;
    private static long refreshedAt = Long.MIN_VALUE / 2;
    private static boolean refreshing;
    private static long presenceAt = Long.MIN_VALUE / 2;
    /** The server the last fetch saw the player on: a friend counts as joining only after that. */
    private static String toastBase;
    /** Played-with names already asked about, lower case: true for those who use Discovery. */
    private static final Map<String, Boolean> uses = new HashMap<>();
    private static boolean checking;

    private Friends() {
    }

    public static Snapshot snapshot() {
        return snapshot;
    }

    private static synchronized Backend backend() {
        // The stand-in only in a development run: in a release it would let anyone fake a friends list.
        boolean stub = Telemetry.config().friendsStub && net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment();
        if (backend == null || stub != backendStub) {
            backend = stub ? new StubBackend() : new LiveBackend();
            backendStub = stub;
            refreshedAt = Long.MIN_VALUE / 2;
            uses.clear();
        }
        return backend;
    }

    // ------------------------------------------------------------------
    // Fetching
    // ------------------------------------------------------------------

    /** Fetch again, unless it was done in the last half minute (or {@code force}). */
    public static void refresh(boolean force) {
        refresh(force ? 0 : Pacing.friendsPageMs());
    }

    private static synchronized void refresh(long maxAge) {
        long now = System.currentTimeMillis();
        if (refreshing || now - refreshedAt < maxAge) {
            return;
        }
        // Background refreshes wait while the API is backing us off; a forced one (after the
        // player did something, or the first load) still goes.
        if (maxAge > 0 && !Pacing.allowed() && snapshot.loaded()) {
            return;
        }
        refreshing = true;
        refreshedAt = now;
        Backend b = backend();
        WORKER.execute(() -> {
            try {
                Snapshot next = b.fetch();
                Snapshot before = snapshot;
                snapshot = sorted(next);
                toasts(before, snapshot);
            } catch (Exception e) {
                FeaturedClient.LOGGER.debug("Friends: fetch failed: {}", e.toString());
                if (!snapshot.loaded()) {
                    snapshot = new Snapshot(false, "Couldn't reach serverlist.cc. Try again in a bit.", List.of(), List.of(), List.of(), List.of());
                }
            } finally {
                synchronized (Friends.class) {
                    refreshing = false;
                }
            }
        });
    }

    /** Online on listed servers first, then unlisted, then offline; by name within each. */
    private static Snapshot sorted(Snapshot s) {
        List<Friend> f = new ArrayList<>(s.friends());
        f.sort(Comparator.<Friend>comparingInt(x -> x.on() != null ? 0 : x.online() ? 1 : 2)
                .thenComparing(x -> x.name().toLowerCase(Locale.ROOT)));
        return new Snapshot(s.loaded(), s.unavailable(), List.copyOf(f), s.incoming(), s.outgoing(), s.blocked());
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    /** Sends a request by name; {@code done} gets the outcome on the client thread. */
    public static void request(String name, Consumer<Result> done) {
        String n = name.trim();
        if (!NAME.matcher(n).matches()) {
            done.accept(new Result(false, "That isn't a Minecraft username."));
            return;
        }
        for (Friend f : snapshot.friends()) {
            if (f.name().equalsIgnoreCase(n)) {
                done.accept(new Result(false, f.name() + " is already a friend."));
                return;
            }
        }
        for (Person p : snapshot.outgoing()) {
            if (p.name().equalsIgnoreCase(n)) {
                done.accept(new Result(false, "Already sent to " + p.name() + "."));
                return;
            }
        }
        Backend b = backend();
        WORKER.execute(() -> {
            Result r;
            try {
                r = b.request(n);
            } catch (Exception e) {
                r = new Result(false, "Couldn't reach serverlist.cc. Try again in a bit.");
            }
            Result out = r;
            Minecraft.getInstance().execute(() -> done.accept(out));
            refresh(true);
        });
    }

    public static void accept(Person p) {
        act("accept", p.id(), s -> new Snapshot(s.loaded(), s.unavailable(),
                plus(s.friends(), new Friend(p.id(), p.name(), false, null)), minus(s.incoming(), p.id()), s.outgoing(), s.blocked()));
    }

    public static void ignore(Person p) {
        act("ignore", p.id(), s -> new Snapshot(s.loaded(), s.unavailable(), s.friends(), minus(s.incoming(), p.id()), s.outgoing(), s.blocked()));
    }

    public static void cancel(Person p) {
        act("cancel", p.id(), s -> new Snapshot(s.loaded(), s.unavailable(), s.friends(), s.incoming(), minus(s.outgoing(), p.id()), s.blocked()));
    }

    public static void remove(Friend f) {
        act("remove", f.id(), s -> new Snapshot(s.loaded(), s.unavailable(), minusFriend(s.friends(), f.id()), s.incoming(), s.outgoing(), s.blocked()));
    }

    public static void block(UUID id, String name) {
        act("block", id, s -> new Snapshot(s.loaded(), s.unavailable(), minusFriend(s.friends(), id), minus(s.incoming(), id),
                minus(s.outgoing(), id), plus(s.blocked(), new Person(id, name, System.currentTimeMillis()))));
    }

    public static void unblock(Person p) {
        act("unblock", p.id(), s -> new Snapshot(s.loaded(), s.unavailable(), s.friends(), s.incoming(), s.outgoing(), minus(s.blocked(), p.id())));
    }

    /** Applies the change on screen straight away, then tells the other end and fetches again. */
    private static void act(String action, UUID id, java.util.function.UnaryOperator<Snapshot> local) {
        snapshot = sorted(local.apply(snapshot));
        Backend b = backend();
        WORKER.execute(() -> {
            try {
                b.act(action, id);
            } catch (Exception e) {
                FeaturedClient.LOGGER.debug("Friends: {} failed: {}", action, e.toString());
            }
            refresh(true);
        });
    }

    /** The privacy settings changed: tell serverlist.cc. */
    public static void pushSettings() {
        Backend b = backend();
        TelemetryConfig c = Telemetry.config();
        WORKER.execute(() -> {
            try {
                b.settings(c);
            } catch (Exception e) {
                FeaturedClient.LOGGER.debug("Friends: settings not sent: {}", e.toString());
            }
        });
    }

    private static List<Person> minus(List<Person> list, UUID id) {
        List<Person> out = new ArrayList<>(list);
        out.removeIf(p -> p.id().equals(id));
        return out;
    }

    private static List<Friend> minusFriend(List<Friend> list, UUID id) {
        List<Friend> out = new ArrayList<>(list);
        out.removeIf(f -> f.id().equals(id));
        return out;
    }

    private static <T> List<T> plus(List<T> list, T item) {
        List<T> out = new ArrayList<>(list);
        out.add(item);
        return out;
    }

    // ------------------------------------------------------------------
    // Played with recently
    // ------------------------------------------------------------------

    /** People from {@link PlayedWith} who use Discovery and aren't friends or asked already, newest first. */
    public static List<PlayedWith.Entry> suggestions(int max) {
        Snapshot s = snapshot;
        Set<UUID> taken = new HashSet<>();
        s.friends().forEach(f -> taken.add(f.id()));
        s.incoming().forEach(p -> taken.add(p.id()));
        s.outgoing().forEach(p -> taken.add(p.id()));
        s.blocked().forEach(p -> taken.add(p.id()));
        Set<String> takenNames = new HashSet<>();
        s.outgoing().forEach(p -> takenNames.add(p.name().toLowerCase(Locale.ROOT)));
        s.friends().forEach(f -> takenNames.add(f.name().toLowerCase(Locale.ROOT)));
        List<PlayedWith.Entry> out = new ArrayList<>();
        synchronized (uses) {
            for (PlayedWith.Entry e : PlayedWith.entries()) {
                String key = e.name().toLowerCase(Locale.ROOT);
                if (Boolean.TRUE.equals(uses.get(key)) && !taken.contains(e.id()) && !takenNames.contains(key)) {
                    out.add(e);
                    if (out.size() >= max) {
                        break;
                    }
                }
            }
        }
        return out;
    }

    /** Asks which of the people played with use Discovery, for the names not asked about yet. */
    public static void checkPlayedWith() {
        List<String> ask = new ArrayList<>();
        synchronized (uses) {
            if (checking) {
                return;
            }
            for (PlayedWith.Entry e : PlayedWith.entries()) {
                String key = e.name().toLowerCase(Locale.ROOT);
                if (!uses.containsKey(key) && ask.size() < 50) {
                    ask.add(key);
                }
            }
            if (ask.isEmpty()) {
                return;
            }
            checking = true;
        }
        Backend b = backend();
        WORKER.execute(() -> {
            try {
                Set<String> yes = b.usingDiscovery(ask);
                synchronized (uses) {
                    for (String n : ask) {
                        uses.put(n, yes.contains(n));
                    }
                }
            } catch (Exception e) {
                FeaturedClient.LOGGER.debug("Friends: played-with lookup failed: {}", e.toString());
            } finally {
                synchronized (uses) {
                    checking = false;
                }
            }
        });
    }

    // ------------------------------------------------------------------
    // In game
    // ------------------------------------------------------------------

    /** Every client tick: count who's around, and keep presence and the toast up to date. */
    public static void clientTick(Minecraft mc) {
        PlayedWith.tick(mc);
        if (mc.getConnection() == null || mc.isLocalServer()) {
            return;
        }
        TelemetryConfig c = Telemetry.config();
        if (c.friendsToast && !snapshot.friends().isEmpty()) {
            refresh(Pacing.friendsGameMs());
        }
        // Play stats already say where the player is; with them off, friends say it themselves,
        // but only to a player who has friends: nobody else's location is worth sending.
        long now = System.currentTimeMillis();
        if (!Telemetry.enabled() && !snapshot.friends().isEmpty() && !"nobody".equals(c.friendsShow) && !c.friendsOffline
                && now - presenceAt > Pacing.presenceMs() && Pacing.allowed()) {
            presenceAt = now;
            String slug = currentSlug(mc);
            Backend b = backend();
            WORKER.execute(() -> {
                try {
                    b.presence(slug);
                } catch (Exception e) {
                    FeaturedClient.LOGGER.debug("Friends: presence not sent: {}", e.toString());
                }
            });
        }
    }

    /** The listed server the player is on, or null. */
    static String currentSlug(Minecraft mc) {
        ServerData sd = mc.getCurrentServer();
        return sd == null || mc.isLocalServer() ? null : ServerListApi.slugFor(sd.ip);
    }

    /** One toast per friend who has turned up on the server the player is already on. */
    private static void toasts(Snapshot before, Snapshot after) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            String mine = currentSlug(mc);
            String base = toastBase;
            toastBase = mine;
            if (mine == null || !mine.equals(base) || !before.loaded() || !Telemetry.config().friendsToast) {
                return;
            }
            Set<UUID> wereHere = new HashSet<>();
            for (Friend f : before.friends()) {
                if (mine.equals(f.on())) {
                    wereHere.add(f.id());
                }
            }
            for (Friend f : after.friends()) {
                if (mine.equals(f.on()) && !wereHere.contains(f.id())) {
                    SystemToast.add(Compat.toasts(mc), Compat.FRIEND_TOAST,
                            Component.literal(f.name() + " joined"), Component.literal("They're on this server too"));
                }
            }
        });
    }
}
