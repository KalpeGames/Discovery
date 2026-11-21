package cc.serverlist.featured.telemetry;

import cc.serverlist.featured.FeaturedClient;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * {@code config/serverlist.json}: the player's settings, and the install id. The settings
 * screen ({@code ui/SettingsScreen}) edits everything but {@code install}.
 *
 * <pre>
 * {
 *   "telemetry": true,          // sharing play stats with serverlist.cc; false stops everything
 *   "install": "…uuid…",        // made up on first run; tells two PCs on one account apart
 *   "noticeSeen": false,        // the first-open notice on the Discovery page has been shown
 *   "titleButton": "auto",      // auto | multiplayer | realms | mods | corner | hidden
 *   "pauseButton": true,        // the Discovery button on the pause menu
 *   "showRecent": true,         // the "Recently played" row
 *   "showFavourites": true,     // the "Favourites" row
 *   "sidePadding": 0,           // 0-5: room left and right of the page, which scales down to fit
 *   "showCracked": false,       // cracked servers in search, browsing and their own strip
 *   "sort": "top",              // see ui/Browse.Sort; anything but "top" browses the full list
 *   "version": "any",           // see ui/Browse.Version
 *   "hideFull": false,          // leave out servers at their player cap
 *   "hideEmpty": false,         // leave out servers with nobody on
 *   "friendsShow": "friends",   // friends | nobody: who sees which server you're on
 *   "friendsOffline": false,    // appear offline to friends
 *   "friendsFrom": "anyone",    // anyone | played | nobody: who can send a friend request
 *   "friendsToast": false,      // a toast when a friend joins the server you're on
 *   "friendsStub": false        // testing only: friends from a local stand-in, not serverlist.cc
 * }
 * </pre>
 */
public final class TelemetryConfig {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("serverlist.json");

    public boolean telemetry = true;
    public String install = UUID.randomUUID().toString();
    public boolean noticeSeen;
    public String titleButton = "auto";
    public boolean pauseButton = true;
    public boolean showRecent = true;
    public boolean showFavourites = true;
    /** 0 (none) to {@link #SIDE_PADDING_MAX}: the page's side margins; see FeaturedScreen.layout. */
    public int sidePadding;
    public static final int SIDE_PADDING_MAX = 5;
    public boolean showCracked;
    public String sort = "top";
    public String version = "any";
    public boolean hideFull;
    public boolean hideEmpty;
    /** Who sees which server you're on: "friends" or "nobody". */
    public String friendsShow = "friends";
    public boolean friendsOffline;
    /** Who can send you a friend request: "anyone", "played" (people you've played with) or "nobody". */
    public String friendsFrom = "anyone";
    /** A toast when a friend joins the server you're on. */
    public boolean friendsToast;
    /** Friends from a local stand-in instead of serverlist.cc, until the API has them. Not in the settings screen. */
    public boolean friendsStub;

    private TelemetryConfig() {
    }

    public static TelemetryConfig load() {
        TelemetryConfig c = new TelemetryConfig();
        if (!Files.exists(FILE)) {
            c.save();
            return c;
        }
        try {
            JsonObject o = JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8)).getAsJsonObject();
            if (o.has("telemetry")) {
                c.telemetry = o.get("telemetry").getAsBoolean();
            }
            if (o.has("install") && o.get("install").getAsString().length() == 36) {
                c.install = o.get("install").getAsString();
            }
            if (o.has("noticeSeen")) {
                c.noticeSeen = o.get("noticeSeen").getAsBoolean();
            }
            if (o.has("titleButton")) {
                c.titleButton = o.get("titleButton").getAsString();
            }
            if (o.has("pauseButton")) {
                c.pauseButton = o.get("pauseButton").getAsBoolean();
            }
            if (o.has("showRecent")) {
                c.showRecent = o.get("showRecent").getAsBoolean();
            }
            if (o.has("sidePadding")) {
                c.sidePadding = Math.max(0, Math.min(SIDE_PADDING_MAX, o.get("sidePadding").getAsInt()));
            }
            if (o.has("showFavourites")) {
                c.showFavourites = o.get("showFavourites").getAsBoolean();
            }
            if (o.has("showCracked")) {
                c.showCracked = o.get("showCracked").getAsBoolean();
            }
            if (o.has("sort")) {
                c.sort = o.get("sort").getAsString();
                // "players" was the default before Top existed; a file from then means the default.
                if (!o.has("sortV") && "players".equals(c.sort)) {
                    c.sort = "top";
                }
            }
            if (o.has("version")) {
                c.version = o.get("version").getAsString();
            }
            if (o.has("hideFull")) {
                c.hideFull = o.get("hideFull").getAsBoolean();
            }
            if (o.has("hideEmpty")) {
                c.hideEmpty = o.get("hideEmpty").getAsBoolean();
            }
            if (o.has("friendsShow")) {
                c.friendsShow = o.get("friendsShow").getAsString();
            }
            if (o.has("friendsOffline")) {
                c.friendsOffline = o.get("friendsOffline").getAsBoolean();
            }
            if (o.has("friendsFrom")) {
                c.friendsFrom = o.get("friendsFrom").getAsString();
            }
            if (o.has("friendsToast")) {
                c.friendsToast = o.get("friendsToast").getAsBoolean();
            }
            if (o.has("friendsStub")) {
                c.friendsStub = o.get("friendsStub").getAsBoolean();
            }
        } catch (Exception e) {
            FeaturedClient.LOGGER.warn("Could not read {}: {}", FILE, e.toString());
        }
        return c;
    }

    public void save() {
        JsonObject o = new JsonObject();
        o.addProperty("telemetry", telemetry);
        o.addProperty("install", install);
        o.addProperty("noticeSeen", noticeSeen);
        o.addProperty("titleButton", titleButton);
        o.addProperty("pauseButton", pauseButton);
        o.addProperty("showRecent", showRecent);
        o.addProperty("showFavourites", showFavourites);
        o.addProperty("sidePadding", sidePadding);
        o.addProperty("showCracked", showCracked);
        o.addProperty("sort", sort);
        o.addProperty("sortV", 2);
        o.addProperty("version", version);
        o.addProperty("hideFull", hideFull);
        o.addProperty("hideEmpty", hideEmpty);
        o.addProperty("friendsShow", friendsShow);
        o.addProperty("friendsOffline", friendsOffline);
        o.addProperty("friendsFrom", friendsFrom);
        o.addProperty("friendsToast", friendsToast);
        if (friendsStub) {
            o.addProperty("friendsStub", true);
        }
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, new GsonBuilder().setPrettyPrinting().create().toJson(o), StandardCharsets.UTF_8);
        } catch (IOException e) {
            FeaturedClient.LOGGER.warn("Could not write {}: {}", FILE, e.toString());
        }
    }
}
