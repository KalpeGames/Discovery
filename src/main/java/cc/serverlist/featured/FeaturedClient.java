package cc.serverlist.featured;

import cc.serverlist.featured.api.ServerListApi;
import cc.serverlist.featured.telemetry.Telemetry;
import cc.serverlist.featured.update.Updater;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point. The mod has no state to set up: the title-screen button is added by a mixin, and
 * the server list is fetched the first time a title screen appears so the Featured page opens
 * with data already in hand rather than a spinner.
 */
public final class FeaturedClient implements ClientModInitializer {
    public static final String MOD_ID = "serverlist";
    public static final Logger LOGGER = LoggerFactory.getLogger("serverlist-featured");
    /** Set once the development auto-open (see TitleScreenMixin) has fired, so it fires only once. */
    public static boolean autoOpened;
    /**
     * True while the title screen's Discovery button sits in the right half of the Realms row, so
     * Realms' notification icons are drawn in the left half instead (RealmsNotificationsScreenMixin).
     */
    public static volatile boolean discoveryBesideRealms;
    /** How far the right end of the Realms row moves when Discovery takes its right half. */
    public static final int DISCOVERY_HALF = 102;

    /**
     * A testing override from the environment, honoured only in a development run. A released
     * jar ignores them, so nothing in a player's launcher setup can point the updater, the API
     * or the identity check somewhere else.
     */
    public static String devEnv(String key) {
        if (!net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment()) {
            return null;
        }
        String v = System.getenv(key);
        return v == null || v.isBlank() ? null : v.trim();
    }

    @Override
    public void onInitializeClient() {
        LOGGER.info("Discovery ready (data: {})", ServerListApi.BASE);
        Telemetry.start();
        Updater.start();
        cc.serverlist.featured.api.Pacing.maybeRefresh();
        ServerListApi.warmAddressIndex();
    }
}
