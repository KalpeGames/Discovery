package cc.serverlist.featured.ui;

import cc.serverlist.featured.api.Featured;
import cc.serverlist.featured.api.ServerListApi;
import cc.serverlist.featured.api.TrackedServer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.util.List;

/**
 * Where each server's icon and banner artwork come from.
 *
 * <p>Icons: the tracker's cached copy first (served immutably by hash, there before any ping),
 * else the favicon the live ping brought back.
 *
 * <p>Banners: whatever {@code /v1/featured} says the server has — the API holds a resized copy
 * of laby.net's in-game render of the network's hub, or failing that Lunar Client's background,
 * at three sizes. A server with none gets a colour block, in the network's own brand colour when
 * the API knows it and the icon's dominant colour otherwise.
 *
 * <p>Which size is drawn goes by how many real pixels the slot covers, so a card at a large GUI
 * scale gets the 800-wide copy and not the 320 one stretched. While the right size loads, the best
 * copy already on the GPU stands in, so opening a server's page shows its card art at once and
 * sharpens when the hero-size copy lands. The page prefetches what is about to scroll into view,
 * and the title screen warms the first screen of every row, so the art is there on first open.
 */
public final class Artwork {
    /** The copies the API serves, smallest first; all 2.5:1, drawn cover-cropped. */
    enum Size {
        CARD(320, ""),
        WIDE(800, "@wide"),
        HERO(1280, "@4x");

        final int width;
        final String suffix;

        Size(int width, String suffix) {
            this.width = width;
            this.suffix = suffix;
        }
    }

    private Artwork() {
    }

    public static RemoteTextures.Tex icon(TrackedServer s, ServerData live) {
        String key = "icon/" + s.slug();
        RemoteTextures.Tex ready = RemoteTextures.ready(key);
        if (ready != null) {
            return ready;
        }
        if (s.icon() != null && !RemoteTextures.failed(key)) {
            return RemoteTextures.get(key, ServerListApi.BASE + s.icon());
        }
        String pingKey = "pingicon/" + s.slug();
        ready = RemoteTextures.ready(pingKey);
        if (ready != null) {
            return ready;
        }
        byte[] bytes = live == null ? null : live.getIconBytes();
        if (bytes != null && !RemoteTextures.failed(pingKey)) {
            return RemoteTextures.upload(pingKey, bytes) ? RemoteTextures.ready(pingKey) : null;
        }
        return null;
    }

    /**
     * The banner for a slot {@code w} GUI pixels wide, or {@code null} while nothing is loaded
     * or when there is none. {@code large} is the hero and the server page, which take the
     * 1280-wide copy whatever the width.
     */
    public static RemoteTextures.Tex banner(TrackedServer s, int w, boolean large) {
        Size want = sizeFor(s, w, large);
        if (want == null) {
            return null;
        }
        RemoteTextures.Tex tex = request(s, want);
        if (tex != null) {
            return tex;
        }
        // Meanwhile whatever is already loaded, sharpest first.
        Size[] sizes = Size.values();
        for (int i = sizes.length - 1; i >= 0; i--) {
            if (sizes[i] != want && RemoteTextures.has(key(s, sizes[i]))) {
                return RemoteTextures.ready(key(s, sizes[i]));
            }
        }
        return null;
    }

    /** Starts loading what {@link #banner} and {@link #icon} would draw, without drawing it. */
    public static void prefetch(TrackedServer s, int w, boolean large, boolean withBanner) {
        if (s.icon() != null) {
            String key = "icon/" + s.slug();
            if (!RemoteTextures.has(key) && !RemoteTextures.failed(key)) {
                RemoteTextures.get(key, ServerListApi.BASE + s.icon());
            }
        }
        Size want = withBanner ? sizeFor(s, w, large) : null;
        if (want != null && !RemoteTextures.has(key(s, want))) {
            request(s, want);
        }
    }

    /**
     * Loads the first screen of the page before it is opened: the hero at full size and, in
     * every row, the cards a strip shows before it is scrolled, at the size they'll be drawn.
     */
    public static void warm(Featured page) {
        int screenW = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int cardW = FeaturedScreen.cardWidthFor(screenW);
        int shown = FeaturedScreen.columnsFor(screenW) + 1;
        if (page.hero() != null) {
            prefetch(page.hero(), screenW, true, true);
        }
        for (Featured.Row row : page.rows()) {
            List<TrackedServer> servers = row.servers();
            for (int i = 0; i < Math.min(shown, servers.size()); i++) {
                prefetch(servers.get(i), cardW, false, true);
            }
        }
    }

    private static Size sizeFor(TrackedServer s, int w, boolean large) {
        if (large && s.bannerHero() != null) {
            return Size.HERO;
        }
        double pixels = w * Minecraft.getInstance().getWindow().getGuiScale();
        // The small copy up to a quarter over its width; a little softness beats 2.5× the bytes.
        if (s.bannerWide() != null && (pixels > Size.CARD.width * 1.25 || s.bannerCard() == null)) {
            return Size.WIDE;
        }
        if (s.bannerCard() != null) {
            return Size.CARD;
        }
        return s.bannerHero() != null ? Size.HERO : null;
    }

    private static RemoteTextures.Tex request(TrackedServer s, Size size) {
        String path = switch (size) {
            case CARD -> s.bannerCard();
            case WIDE -> s.bannerWide();
            case HERO -> s.bannerHero();
        };
        String key = key(s, size);
        return path == null || RemoteTextures.failed(key) ? null : RemoteTextures.get(key, ServerListApi.BASE + path, true);
    }

    private static String key(TrackedServer s, Size size) {
        return "banner/" + s.slug() + size.suffix;
    }

    /**
     * The colour that stands for a server: the dominant vivid colour of its logo, which is
     * what a player recognises it by, else the brand colour the API knows (Lunar's file) while
     * the icon is still loading or when there is none, else 0 for "nothing known".
     */
    public static int accent(TrackedServer s, ServerData live) {
        RemoteTextures.Tex icon = icon(s, live);
        if (icon != null && icon.accent() != 0) {
            return icon.accent();
        }
        return s.color();
    }
}
