package cc.serverlist.featured.friends;

import cc.serverlist.featured.api.ServerListApi;
import cc.serverlist.featured.telemetry.Telemetry;
import cc.serverlist.featured.telemetry.TelemetryConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Friends on serverlist.cc. Every call carries the play-stats identity token as a bearer.
 *
 * <pre>
 * GET  /v1/friends
 *   → { friends:  [{ uuid, name, online: bool, slug: string|null }],   // slug null + online = unlisted server
 *       incoming: [{ uuid, name, at: ISO-8601 }],
 *       outgoing: [{ uuid, name, at }],                                   // expire after 30 days
 *       blocked:  [{ uuid, name, at }] }
 *   Presence honours each friend's settings: "nobody" or appear-offline reads as offline.
 *
 * POST /v1/friends/request  { name }
 *   200 { result: "sent" | "friends" }        // "friends" when they had already asked you
 *   404 { error: "unknown" }                   // no Minecraft account by that name
 *   404 { error: "no_discovery" }              // the account has never used Discovery
 *   409 { error: "already" | "pending" }
 *   403 { error: "closed" }                    // their "requests from" setting, or they blocked you
 *   429                                        // too many requests
 *
 * POST /v1/friends/{accept|ignore|cancel|remove|block|unblock}  { uuid }   → 204
 *   Ignore and block are silent: the other side is never told.
 *
 * POST /v1/friends/settings  { show: "friends"|"nobody", offline: bool, from: "anyone"|"played"|"nobody" }
 * POST /v1/friends/lookup    { names: [..] }  → { uses: [lower-case names that use Discovery] }
 * POST /v1/friends/presence  { slug: string|null }   // only sent while play stats are off
 * </pre>
 */
final class LiveBackend implements Friends.Backend {
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final String BASE = ServerListApi.BASE + "/v1/friends";

    @Override
    public Friends.Snapshot fetch() throws IOException {
        String token = Telemetry.identityToken();
        if (token == null) {
            return unavailable("Friends need you signed in with a Minecraft account.");
        }
        ServerListApi.Response r = ServerListApi.send("GET", BASE, null, false, token, TIMEOUT);
        if (r.status() == 401) {
            Telemetry.dropToken();
            throw new IOException("token rejected");
        }
        if (r.status() == 404) {
            return unavailable("Friends aren't on serverlist.cc yet. Soon.");
        }
        if (r.status() / 100 != 2) {
            throw new IOException("HTTP " + r.status());
        }
        JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
        List<Friends.Friend> friends = new ArrayList<>();
        for (JsonElement el : array(o, "friends")) {
            JsonObject f = el.getAsJsonObject();
            String slug = f.has("slug") && !f.get("slug").isJsonNull() ? f.get("slug").getAsString() : null;
            boolean online = f.has("online") && f.get("online").getAsBoolean();
            friends.add(new Friends.Friend(UUID.fromString(f.get("uuid").getAsString()), f.get("name").getAsString(),
                    online, online ? slug : null));
        }
        return new Friends.Snapshot(true, null, friends, people(o, "incoming"), people(o, "outgoing"), people(o, "blocked"));
    }

    @Override
    public Friends.Result request(String name) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("name", name);
        ServerListApi.Response r = post("/request", body);
        String error = "";
        String result = "";
        try {
            JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
            error = o.has("error") ? o.get("error").getAsString() : "";
            result = o.has("result") ? o.get("result").getAsString() : "";
        } catch (Exception ignored) {
            // No body to read; the status says enough.
        }
        if (r.status() / 100 == 2) {
            return "friends".equals(result)
                    ? new Friends.Result(true, "They'd asked you too. You're friends now.")
                    : new Friends.Result(true, "Sent to " + name + ".");
        }
        return new Friends.Result(false, switch (error) {
            case "unknown" -> "No Minecraft account is called " + name + ".";
            case "no_discovery" -> name + " doesn't use Discovery yet.";
            case "already" -> name + " is already a friend.";
            case "pending" -> "Already sent to " + name + ".";
            case "closed" -> name + " isn't taking requests.";
            case "blocked" -> "You've blocked " + name + ". Unblock them in settings first.";
            case "self" -> "That's you.";
            case "limit" -> "You've hit the friend limit. Remove someone first.";
            default -> r.status() == 429 ? "That's a lot of requests. Try again later." : "That didn't go through. Try again in a bit.";
        });
    }

    @Override
    public void act(String action, UUID id) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", id.toString());
        check(post("/" + action, body));
    }

    @Override
    public void settings(TelemetryConfig c) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("show", c.friendsShow);
        body.addProperty("offline", c.friendsOffline);
        body.addProperty("from", c.friendsFrom);
        check(post("/settings", body));
    }

    @Override
    public Set<String> usingDiscovery(List<String> names) throws IOException {
        JsonObject body = new JsonObject();
        JsonArray a = new JsonArray();
        names.forEach(a::add);
        body.add("names", a);
        ServerListApi.Response r = check(post("/lookup", body));
        Set<String> out = new HashSet<>();
        for (JsonElement el : array(JsonParser.parseString(r.body()).getAsJsonObject(), "uses")) {
            out.add(el.getAsString().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    @Override
    public void presence(String slug) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("slug", slug);
        check(post("/presence", body));
    }

    private ServerListApi.Response post(String path, JsonObject body) throws IOException {
        String token = Telemetry.identityToken();
        if (token == null) {
            throw new IOException("no identity");
        }
        ServerListApi.Response r = ServerListApi.send("POST", BASE + path, body.toString().getBytes(StandardCharsets.UTF_8), false, token, TIMEOUT);
        if (r.status() == 401) {
            Telemetry.dropToken();
        }
        return r;
    }

    private static ServerListApi.Response check(ServerListApi.Response r) throws IOException {
        if (r.status() / 100 != 2) {
            throw new IOException("HTTP " + r.status());
        }
        return r;
    }

    private static JsonArray array(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
    }

    private static List<Friends.Person> people(JsonObject o, String key) {
        List<Friends.Person> out = new ArrayList<>();
        for (JsonElement el : array(o, key)) {
            JsonObject p = el.getAsJsonObject();
            long at = p.has("at") ? Instant.parse(p.get("at").getAsString()).toEpochMilli() : System.currentTimeMillis();
            out.add(new Friends.Person(UUID.fromString(p.get("uuid").getAsString()), p.get("name").getAsString(), at));
        }
        return out;
    }

    private static Friends.Snapshot unavailable(String why) {
        return new Friends.Snapshot(true, why, List.of(), List.of(), List.of(), List.of());
    }
}
