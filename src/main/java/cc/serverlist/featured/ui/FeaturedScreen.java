package cc.serverlist.featured.ui;

import cc.serverlist.featured.compat.Compat;

import cc.serverlist.featured.FeaturedClient;
import cc.serverlist.featured.api.Categories;
import cc.serverlist.featured.api.Featured;
import cc.serverlist.featured.api.ServerListApi;
import cc.serverlist.featured.api.TrackedServer;
import cc.serverlist.featured.friends.Friends;
import cc.serverlist.featured.telemetry.Favourites;
import cc.serverlist.featured.telemetry.RecentServers;
import cc.serverlist.featured.telemetry.Telemetry;
import cc.serverlist.featured.telemetry.TelemetryConfig;
import cc.serverlist.featured.update.Updater;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import com.mojang.blaze3d.platform.InputConstants;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Featured Servers page: a hero and a strip of server cards per category.
 *
 * <p>The look borrows Minecraft's own furniture wherever it has some â€” the list background and
 * separators from the multiplayer screen, the button, scrollbar and page-arrow sprites, the ping
 * bars â€” and lays it out the way a streaming front page does: a hero at the top, then one
 * sideways-scrolling strip of cards per category, the page itself scrolling down through them.
 *
 * <p>Ranking and the card data come from serverlist.cc's tracker; each visible card is also
 * pinged from this client the way the vanilla list does it, so the players-online figure and
 * the latency are what <em>this</em> player would get.
 */
public class FeaturedScreen extends Screen {
    // ---- geometry ------------------------------------------------------
    private static final int HEADER_H = 32;
    /** The bar's controls are this tall and sit centred above the separator. */
    private static final int BAR_CONTROL_H = 20;
    private static final int BAR_Y = (HEADER_H - 2 - BAR_CONTROL_H) / 2;
    private static final float TITLE_SCALE = 1.5f;
    /** The line under the last strip: how many servers there are to browse. */
    private static final int PAGE_FOOTER_H = 26;
    /** The first-open notice about play stats: a band across the foot of the page. */
    private static final int NOTICE_H = 62;
    private static final String PRIVACY_URL = "https://serverlist.cc/privacy";
    /** Room for the magnifier at the search field's left end and the clear cross at its right. */
    private static final int SEARCH_ICON_W = 22;
    private static final int SEARCH_CLEAR_W = 16;
    private static final int ROW_TITLE_H = 22;
    private static final int ROW_GAP = 12;
    private static final float ROW_TITLE_SCALE = 1.5f;
    /** How much of the next card shows past the strip's edge, so it reads as scrollable. */
    private static final int PEEK = 24;
    /** The arrows are vanilla buttons with the book-page sprite inside, so they read as buttons. */
    private static final int ARROW_W = 14;
    private static final int ARROW_H = 14;
    private static final int ARROW_SPRITE_W = 23;
    private static final int ARROW_SPRITE_H = 13;
    private static final int FOOTER_H = 0;
    static final int GRID_PAD = 8;
    private static final int GAP = 6;
    /**
     * Tiles, Roblox-style: the art at 16:9, the name and the count under it, six to a row where
     * there is room. 68 lets six fit on a 1080p screen at the default GUI scale (480 wide).
     */
    private static final int TILE_MIN_W = 68;
    private static final int MAX_COLUMNS = 6;
    /** Name and count under a tile's art. */
    private static final int TILE_TEXT_H = 25;
    /** A tile's full height; follows the width, so it is set in {@link #layout}. */
    private static int CARD_H = 64;
    /** A tile's width this frame, set in {@link #layout}. */
    private static int cardW = TILE_MIN_W;
    /** The compact card: icon, name, count and Join on one band, no art. Recently played uses it. */
    private static final int COMPACT_H = 30;
    /** A Jump back in card's width: four to the row, set in {@link #layout}. */
    private static int compactW = 100;
    /** The narrowest a Jump back in / Favourites card gets before the row shows fewer. */
    private static final int COMPACT_MIN_W = 128;
    /** Corner radius of tiles, cards and heads. */
    static final int RADIUS = 4;
    /** The friends row at the top of the page: heads, names, where they are. */
    private static final int HEAD = 38;
    private static final int FRIEND_W = 50;
    /** Head, the name under it, and a second grey line when any tile has one. */
    private static final int FRIENDS_NAME_H = HEAD + 4 + 9;
    private static final int FRIENDS_SUB_H = 10;
    // The page's own colours: flat and dark, so the server art is the colour on the page.
    static final int PAGE_BG = 0xFF17171A;
    static final int HEADER_BG = 0xFF111113;
    static final int TILE_BG = 0xFF26262B;
    static final int TILE_HOVER = 0xFF303036;
    private static final int CARD_PAD = 8;
    /** A card's bottom row sits a little closer to the edge than the side padding, to give the status line room. */
    private static final int CARD_BOTTOM_PAD = 6;
    private static final int BANNER_H = 67;
    static final int ICON = 32;
    static final int CHIP_H = 12;
    /** The play button: nearly square, a little wider than tall so the triangle has room. */
    static final int JOIN_W = 26;
    static final int JOIN_H = 18;
    static final int STAR = 9;
    static final int STAR_HIT = 16;
    private static final int HERO_H = 160;
    private static final java.util.regex.Pattern VERSION_LIKE = java.util.regex.Pattern.compile("\\d+\\.\\d+");
    private static final java.util.regex.Pattern MANY_SPACES = java.util.regex.Pattern.compile("\\s{2,}");
    static final int HERO_ICON = 48;
    static final int SCROLLBAR_W = 6;
    private static final int SEARCH_LIMIT = 40;
    /**
     * How many cards the browsing grid holds at most. Only the ones on screen are drawn or
     * pinged, so this bounds the record list and the scrollbar, not the work per frame.
     */
    private static final int BROWSE_LIMIT = 300;
    /** How many cracked servers their strip carries. */
    private static final int CRACKED_ROW_LIMIT = 200;
    /** The line above the grid saying how many servers and how they are sorted. */
    private static final int GRID_BAR_H = 16;
    private static final int CRACKED_COLOR = 0xFFFF7744;

    // ---- palette (vanilla's own greys and chat colours) ----------------
    static final int WHITE = 0xFFFFFFFF;
    static final int GRAY = 0xFFA0A0A0;
    static final int DARK_GRAY = 0xFF555555;
    static final int GREEN = 0xFF55FF55;
    static final int RED = 0xFFFF5555;
    static final int YELLOW = 0xFFFFFF55;
    static final int AQUA = 0xFF55FFFF;

    // ---- vanilla textures and sprites ----------------------------------
    static final Identifier MENU_LIST_BACKGROUND = Identifier.withDefaultNamespace("textures/gui/menu_list_background.png");
    private static final Identifier MENU_BACKGROUND = Identifier.withDefaultNamespace("textures/gui/menu_background.png");
    private static final Identifier HEADER_SEPARATOR = Identifier.withDefaultNamespace("textures/gui/header_separator.png");
    static final Identifier TEXT_FIELD = Identifier.withDefaultNamespace("widget/text_field");
    private static final Identifier TEXT_FIELD_HIGHLIGHTED = Identifier.withDefaultNamespace("widget/text_field_highlighted");
    private static final Identifier SEARCH_ICON = Identifier.withDefaultNamespace("icon/search");
    private static final Identifier PAGE_BACKWARD = Identifier.withDefaultNamespace("widget/page_backward");
    private static final Identifier PAGE_BACKWARD_HIGHLIGHTED = Identifier.withDefaultNamespace("widget/page_backward_highlighted");
    private static final Identifier PAGE_FORWARD = Identifier.withDefaultNamespace("widget/page_forward");
    private static final Identifier PAGE_FORWARD_HIGHLIGHTED = Identifier.withDefaultNamespace("widget/page_forward_highlighted");
    static final Identifier BUTTON = Identifier.withDefaultNamespace("widget/button");
    static final Identifier BUTTON_HIGHLIGHTED = Identifier.withDefaultNamespace("widget/button_highlighted");
    static final Identifier BUTTON_DISABLED = Identifier.withDefaultNamespace("widget/button_disabled");
    private static final Identifier SCROLLER = Identifier.withDefaultNamespace("widget/scroller");
    private static final Identifier SCROLLER_BACKGROUND = Identifier.withDefaultNamespace("widget/scroller_background");
    private static final Identifier[] PING_SPRITES = {
            Identifier.withDefaultNamespace("server_list/ping_1"),
            Identifier.withDefaultNamespace("server_list/ping_2"),
            Identifier.withDefaultNamespace("server_list/ping_3"),
            Identifier.withDefaultNamespace("server_list/ping_4"),
            Identifier.withDefaultNamespace("server_list/ping_5")
    };
    private static final Identifier[] PINGING_SPRITES = {
            Identifier.withDefaultNamespace("server_list/pinging_1"),
            Identifier.withDefaultNamespace("server_list/pinging_2"),
            Identifier.withDefaultNamespace("server_list/pinging_3"),
            Identifier.withDefaultNamespace("server_list/pinging_4"),
            Identifier.withDefaultNamespace("server_list/pinging_5")
    };
    private static final Identifier UNREACHABLE_SPRITE = Identifier.withDefaultNamespace("server_list/unreachable");
    private static final Identifier INCOMPATIBLE_SPRITE = Identifier.withDefaultNamespace("server_list/incompatible");

    /** Pings run on their own threads, as vanilla's list does; the pool outlives any one screen. */
    private static final ExecutorService PING_POOL = Executors.newFixedThreadPool(5, r -> {
        Thread t = new Thread(r, "serverlist-ping");
        t.setDaemon(true);
        return t;
    });

    /**
     * One strip of cards on the page, as the API laid it out. {@code servers} is what is drawn;
     * {@code source} is the row it came from, which grows as pages are appended.
     */
    private record Row(String id, Component label, String subtitle, List<TrackedServer> servers, Featured.Row source) {
        static Row of(Featured.Row r) {
            return new Row(r.id(), Component.literal(r.title()), r.subtitle(), r.servers(), r);
        }

        /** Top servers is a block of two rows of tiles, not a strip, with See all for the rest. */
        boolean grid() {
            return "top".equals(id);
        }

        /** Compact cards are a quarter of the row, four across. */
        int cardW() {
            return compact() ? compactW : cardW;
        }

        int pitch() {
            return cardW() + GAP;
        }

        /** The tiles under the title: one band, or two for the grid. */
        int height() {
            return grid() ? TOP_H : cardH();
        }

        /**
         * Recently played and Favourites are lists of places, not a shop window: the short
         * card, no art.
         */
        boolean compact() {
            return "recent".equals(id) || "favourites".equals(id);
        }

        int cardH() {
            return compact() ? COMPACT_H : CARD_H;
        }
    }

    /**
     * The left button while it is down on the page: what releasing it here would do, and where
     * a drag scrolls from. A card acts on release over the same spot, never on the press.
     */
    private static final class Press {
        final double x;
        final double y;
        final String target;
        final int strip;
        final int pageStart;
        final float stripStart;
        final boolean scrollbar;
        boolean dragging;
        boolean sideways;
        double lastX;
        long lastAt;
        double velocity;

        Press(double x, double y, String target, int strip, int pageStart, float stripStart, boolean scrollbar) {
            this.x = x;
            this.y = y;
            this.target = target;
            this.strip = strip;
            this.pageStart = pageStart;
            this.stripStart = stripStart;
            this.scrollbar = scrollbar;
            this.lastX = x;
            this.lastAt = Util.getMillis();
        }
    }

    /** How far the mouse moves with the button down before it is a drag and not a click. */
    private static final int DRAG_SLOP = 4;

    /** Where a strip is and where it is heading sideways, eased a little each frame. */
    private static final class StripScroll {
        float current;
        float target;
        /** Sideways wheel not yet worth a card: a trackpad sends a swipe as many small pieces. */
        double pending;
    }

    /** A strip as laid out this frame, for the wheel and the arrows: its rect and how far it can go. */
    private record Strip(int rowIndex, int x, int y, int w, int h, int maxScroll) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }

        /** The page arrows live on the title row, right-aligned, clear of the cards. */
        int backX() {
            return x + w - ARROW_W * 2 - 3;
        }

        int forwardX() {
            return x + w - ARROW_W;
        }

        int arrowY() {
            return y - ROW_TITLE_H + (ROW_TITLE_H - 4 - ARROW_H) / 2;
        }
    }

    /**
     * One card as laid out this frame; the click handler reads these back. {@code row} and
     * {@code pos} say where on the page it was â€” {@code hero}, a strip's id, or {@code search}.
     */
    record Card(TrackedServer server, int x, int y, int w, int h, boolean hero, String row, int pos) {
        /** The art's height on a tile: 16:9 of its width. */
        int artH() {
            return w * 9 / 16;
        }

        int joinX() {
            return x + w - (hero ? CARD_PAD : compact() ? 6 : 3) - JOIN_W;
        }

        int joinY() {
            if (h <= COMPACT_H) {
                return y + (h - JOIN_H) / 2;
            }
            if (!hero) {
                return y + artH() - 3 - JOIN_H;
            }
            return y + h - CARD_PAD - FEATURED_ICON / 2 - JOIN_H / 2;
        }

        boolean compact() {
            return !hero && h <= COMPACT_H;
        }

        /** The star's hit box: a comfortable square in the banner's top-right corner. */
        int starX() {
            return x + w - 2 - STAR_HIT;
        }

        int starY() {
            return y + 2;
        }

        int iconX() {
            return x + CARD_PAD;
        }

        int iconY() {
            return hero ? y + h - CARD_PAD - HERO_ICON : y + 1 + BANNER_H - ICON / 2;
        }
    }

    /** A 9Ã—9 star, drawn a pixel at a time so it matches the font's weight at any GUI scale. */
    private static final String[] STAR_ROWS = {
            "....#....",
            "....#....",
            "...###...",
            "#########",
            ".#######.",
            "..#####..",
            "..#####..",
            ".##...##.",
            ".#.....#."
    };

    private Screen parent;
    private final ServerStatusPinger pinger = new ServerStatusPinger();
    /**
     * Servers already in the player's own multiplayer list, so their stars show filled. Held by
     * {@link ServerListApi#siteKey}, so a list entry for {@code tarna.hypixel.net} lights the
     * star on Hypixel.
     */
    final Set<String> saved = new HashSet<>();
    private final Map<String, ServerData> live = new HashMap<>();
    private final List<Card> cards = new ArrayList<>();
    private final List<Strip> strips = new ArrayList<>();
    /** Per-strip sideways scroll, kept by row id so a reload doesn't jump the strips back. */
    private final Map<String, StripScroll> stripScroll = new HashMap<>();

    /** The page as the API laid it out, or null until it has loaded. */
    private Featured featured;
    /**
     * The full tracker list behind search, fetched the first time someone types; null before
     * then, when a query is matched against the featured servers only.
     */
    private List<TrackedServer> searchable;
    /** Search results, when a query is typed; otherwise the page is {@link #rows}. */
    private List<TrackedServer> shown = List.of();
    /** How many servers the grid's search or filters matched, before {@link #BROWSE_LIMIT} cut it. */
    private int shownTotal;
    /** The grid is the filtered full list rather than search results (see {@link Browse}). */
    private boolean browsing;
    /** Top servers' "See all": the grid over every server, most players first, until Back. */
    private boolean seeAll;
    /** Top servers' "See all" link as laid out this frame; 0 wide when not drawn. */
    private int seeAllX, seeAllY, seeAllW;
    /** The friend heads as laid out this frame, for the hover and the click. */
    private final List<FriendHit> friendHits = new ArrayList<>();
    /** The Add box and a friend's menu, over the strip. */
    private final FriendsPanel friendsPanel = new FriendsPanel(this);
    /** The grid bar's "Clear filters" link as laid out this frame, for the click; 0 wide when absent. */
    private int clearFiltersX, clearFiltersY, clearFiltersW;
    private List<Row> rows = List.of();
    /** The server in the hero slot, or null when searching or when the width has no room. */
    private TrackedServer hero;
    private String query = "";
    private TrackedServer selected;
    private int scroll;
    private Press press;
    private int columns = 1;
    private boolean loading;
    private String error;

    private EditBox search;
    private Button retryButton;
    /** Left of the search field while Modrinth has a newer build; see {@link Updater}. */
    private Button updateButton;
    private Button settingsButton;
    private GearButton filterButton;
    private Updater.State shownUpdateState;
    private String shownUpdateLabel;

    private long lastClickAt;
    private TrackedServer lastClicked;

    // What the page reports back (see Telemetry): one impression per card per open, how far
    // along each strip the player got, the search they typed as a length and a count, and why
    // the page closed. Nothing here is a name or a query.
    private static boolean everOpened;
    private long openedAt = System.currentTimeMillis();
    private boolean opened, closed;
    private final Set<String> impressed = new HashSet<>();
    private final Map<String, Integer> reached = new HashMap<>();
    private int searchLen = -1, searchResults;
    private String closeReason;
    private boolean noticeShowing;
    private final List<Button> noticeButtons = new ArrayList<>();

    int gridX, gridY, gridW, gridH;
    int innerX, innerY, innerW, innerH;
    /** The server page that opens over the rows when a card is clicked; inactive most of the time. */
    private final ServerDetail detail = new ServerDetail(this);
    /** The hero's height: its full size on a normal window, no more than half the page on a small one. */
    private int heroH;
    /** The search field's frame; the EditBox sits inside it, between the magnifier and the cross. */
    private int fieldX, fieldW;

    /**
     * True while the player is on a server they joined from this page, so that leaving it
     * comes back here rather than to the multiplayer list (see {@code MinecraftMixin}).
     */
    public static volatile boolean joinedFromPage;

    /**
     * The screen to show after leaving a server. Vanilla lands on the multiplayer list; a server
     * joined from Discovery lands back on Discovery, with the title screen behind it as the
     * multiplayer list would have had. Called from the disconnect hooks (MinecraftMixin, and
     * PauseScreenMixin on 1.21.8).
     */
    public static Screen afterDisconnect(Screen next) {
        if (next instanceof net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen && joinedFromPage) {
            joinedFromPage = false;
            return new FeaturedScreen(new net.minecraft.client.gui.screens.TitleScreen());
        }
        return next;
    }

    public FeaturedScreen(Screen parent) {
        super(Component.literal("Discovery"));
        this.parent = parent;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    protected void init() {
        layout();

        // Back is a square arrow at the left, where an app puts it; the label is a tooltip.
        GearButton back = addRenderableWidget(GearButton.back(GRID_PAD, BAR_Y, BAR_CONTROL_H, b -> {
            if (detail.active()) {
                detail.close();
            } else {
                onClose();
            }
        }));
        back.setTooltip(Tooltip.create(CommonComponents.GUI_BACK));

        // The field's frame and magnifier are drawn by drawHeader; the box itself is borderless
        // text laid inside it, so the icon has room and the hit area is the whole frame.
        EditBox old = search;
        search = new EditBox(font, fieldX + SEARCH_ICON_W, BAR_Y + (BAR_CONTROL_H - 8) / 2,
                fieldW - SEARCH_ICON_W - SEARCH_CLEAR_W, 8, old, Component.literal("Search"));
        search.setBordered(false);
        search.setHint(Component.literal("Search servers...").withStyle(ChatFormatting.DARK_GRAY));
        search.setMaxLength(64);
        search.setResponder(q -> {
            query = q;
            scroll = 0;
            recompute();
            if (q.isBlank()) {
                flushSearch(null);
            }
        });
        addRenderableWidget(search);
        addRenderableWidget(friendsPanel.makeBox());
        Friends.refresh(false);

        retryButton = addRenderableWidget(Button.builder(Component.literal("Retry"), b -> load(true))
                .bounds(tipX(gridX + gridW / 2) - 40, tipY(gridY + gridH / 2 + 6), 80, 20).build());

        // Settings: a square gear left of the search field, like the back arrow on the right.
        settingsButton = addRenderableWidget(new GearButton(settingsX(), BAR_Y, BAR_CONTROL_H, b -> {
            closeReason = "settings";
            Compat.setScreen(minecraft, new SettingsScreen(this, SettingsScreen.Tab.PAGE));
        }));
        settingsButton.setTooltip(Tooltip.create(Component.literal("Settings")));

        // Sort & filter: a funnel left of the gear, with a dot on it while the filters differ
        // from the plain page.
        filterButton = addRenderableWidget(GearButton.funnel(filterX(), BAR_Y, BAR_CONTROL_H, b -> {
            closeReason = "settings";
            Compat.setScreen(minecraft, new SettingsScreen(this, SettingsScreen.Tab.FILTER));
        }));
        filterButton.setTooltip(Tooltip.create(Component.literal("Sort & filter: order by players, version and more, or show cracked servers")));

        updateButton = addRenderableWidget(Button.builder(Component.empty(), b -> Updater.download())
                .bounds(0, BAR_Y, 0, BAR_CONTROL_H).build());
        shownUpdateState = null;
        refreshUpdateButton();

        if (saved.isEmpty()) {
            ServerList list = new ServerList(minecraft);
            list.load();
            for (int i = 0; i < list.size(); i++) {
                String key = ServerListApi.siteKey(list.get(i).ip);
                if (key != null) {
                    saved.add(key);
                }
            }
        }

        if (closed) {
            // Back from a failed join or a disconnect: the same page, opened again.
            closed = false;
            opened = false;
            openedAt = System.currentTimeMillis();
            impressed.clear();
        }
        if (featured == null) {
            load(false);
        } else {
            recompute();
            pageOpened(0, true);
        }
        if (detail.active()) {
            detailChanged(true);
        }

        noticeShowing = !Telemetry.config().noticeSeen && Telemetry.enabled();
        noticeButtons.clear();
        if (noticeShowing) {
            // Two lines of text, then the buttons on a row of their own at the right, so the
            // words never fight the buttons for width.
            int by = screenBottom() - 6 - 20;
            int bx = innerX + innerW - (72 * 3 + 4 * 2);
            noticeButtons.add(addRenderableWidget(Button.builder(Component.literal("OK").withStyle(ChatFormatting.GREEN), b -> dismissNotice())
                    .bounds(bx, by, 72, 20).build()));
            noticeButtons.add(addRenderableWidget(Button.builder(Component.literal("Turn off"), b -> {
                Telemetry.setEnabled(false);
                dismissNotice();
            }).bounds(bx + 76, by, 72, 20).build()));
            noticeButtons.add(addRenderableWidget(Button.builder(Component.literal("Learn more"), b -> openPrivacy())
                    .bounds(bx + 152, by, 72, 20).build()));
        }
        updateButtons();
    }

    private void dismissNotice() {
        Telemetry.markNoticeSeen();
        noticeShowing = false;
        for (Button b : noticeButtons) {
            b.visible = false;
        }
    }

    private void leaveSeeAll() {
        seeAll = false;
        scroll = 0;
        recompute();
    }

    private void openPrivacy() {
        Screen self = this;
        Compat.confirmLink(minecraft, self, PRIVACY_URL);
    }

    /** Once per open: how the page was reached and how long its data took. */
    private void pageOpened(long fetchMs, boolean cached) {
        if (opened) {
            return;
        }
        opened = true;
        String source = parent instanceof net.minecraft.client.gui.screens.TitleScreen ? "title"
                : parent instanceof net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen ? "multiplayer"
                : parent instanceof net.minecraft.client.gui.screens.PauseScreen ? "pause" : "other";
        Telemetry.event("page_open", "source", source, "fetchMs", fetchMs, "cached", cached, "cold", !everOpened);
        everOpened = true;
    }

    /** A card was on screen: counted once per card per open, by where it was shown. */
    private void impression(Card c) {
        String key = c.row() + ":" + c.server().slug();
        if (impressed.add(key)) {
            Telemetry.event("impression", "slug", RecentServers.reportable(c.server()), "row", c.row(), "pos", c.pos());
        }
        reached.merge(c.row(), c.pos(), Math::max);
    }

    /** The search so far, sent when it is over: cleared, closed, or a result joined. */
    private void flushSearch(String picked) {
        if (searchLen < 0) {
            return;
        }
        Telemetry.event("search", "len", searchLen, "results", searchResults, "picked", picked);
        searchLen = -1;
    }

    @Override
    protected void setInitialFocus() {
        // Nothing focused: typing should not start in the search box, and Enter should join.
    }

    /** The featured tile's icon, on its bottom row. */
    private static final int FEATURED_ICON = 28;
    /** How many columns the featured tile spans in Top servers; 0 with no featured server. */
    private static int topSpan;
    /** Top servers' block height under its title, as {@link #topLayout} works it out. */
    private static int TOP_H = 100;

    /**
     * Sizes the Top block. The featured tile is two tile rows tall and three columns wide on
     * a wide window (six tiles' worth), two on a four-column one; narrower than that it takes
     * the whole width and two more rows of tiles go under it.
     */
    private void topLayout(boolean hasFeatured) {
        topSpan = !hasFeatured ? 0 : columns >= 5 ? 3 : columns == 4 ? 2 : columns;
        TOP_H = topSpan == columns ? CARD_H * 4 + GAP * 3 : CARD_H * 2 + GAP;
    }

    private void layout() {
        gridX = 0;
        gridY = HEADER_H;
        gridW = width;
        // Side padding: the page below the header is laid out at the full width and drawn scaled
        // into the space between the margins, so a row still holds as many cards. Its height in
        // page units grows by the same factor, so it still reaches the bottom of the screen.
        int level = Telemetry.config() == null ? 0 : Telemetry.config().sidePadding;
        contentPad = Math.round(width * MAX_SIDE_PADDING * level / TelemetryConfig.SIDE_PADDING_MAX);
        contentScale = (width - 2f * contentPad) / width;
        gridH = Math.round((height - HEADER_H) / contentScale) - FOOTER_H;
        innerX = gridX + GRID_PAD;
        innerY = gridY + GRID_PAD;
        innerW = gridW - GRID_PAD * 2 - SCROLLBAR_W;
        innerH = gridH - GRID_PAD * 2;
        fieldW = clamp(width / 3, 140, 240);
        fieldX = width - GRID_PAD - BAR_CONTROL_H - 4 - fieldW;

        columns = columnsFor(width);
        cardW = cardWidthFor(width);
        CARD_H = cardW * 9 / 16 + TILE_TEXT_H;
        // Four across where each still has room for a name beside its Play button, else fewer.
        int compactCols = clamp((innerW + GAP) / (COMPACT_MIN_W + GAP), 2, 4);
        compactW = (innerW - (compactCols - 1) * GAP) / compactCols;
        heroH = clamp(innerH / 2, 100, HERO_H);
        topLayout(topSpan > 0);
    }

    /** At the largest side padding, each margin is this share of the screen's width. */
    private static final float MAX_SIDE_PADDING = 0.07f;
    /** The page's drawing scale and left margin (screen pixels) under the side-padding setting. */
    static float contentScale = 1f;
    static int contentPad;

    /** Where the page ends on screen, whatever its scale: the notice band sits on this. */
    private int screenBottom() {
        return height - FOOTER_H;
    }

    /** A screen point in page units. The header above the page is never scaled. */
    static double contentX(double mx, double my) {
        return my < HEADER_H ? mx : (mx - contentPad) / contentScale;
    }

    static double contentY(double my) {
        return my < HEADER_H ? my : HEADER_H + (my - HEADER_H) / contentScale;
    }

    private static double cx(MouseButtonEvent e) {
        return contentX(e.x(), e.y());
    }

    private static double cy(MouseButtonEvent e) {
        return contentY(e.y());
    }

    /** A page point back on screen, for tooltips, which are placed after the page is drawn. */
    static int tipX(int x) {
        return Math.round(contentPad + x * contentScale);
    }

    static int tipY(int y) {
        return Math.round(HEADER_H + (y - HEADER_H) * contentScale);
    }

    /** How many whole cards a strip shows on a screen this wide, as {@link #layout} works it out. */
    static int columnsFor(int screenWidth) {
        int inner = screenWidth - GRID_PAD * 2 - SCROLLBAR_W;
        return clamp((inner + GAP) / (TILE_MIN_W + GAP), 2, MAX_COLUMNS);
    }

    /** A strip card's width on a screen this wide: `columns` whole cards and a sliver of the next. */
    static int cardWidthFor(int screenWidth) {
        int inner = screenWidth - GRID_PAD * 2 - SCROLLBAR_W;
        int cols = columnsFor(screenWidth);
        return (inner - (cols - 1) * GAP) / cols;
    }

    @Override
    public void tick() {
        pinger.tick();
        RemoteTextures.trim();
        Friends.refresh(false);
        refreshUpdateButton();
    }

    /**
     * The update button follows {@link Updater}'s state, which changes on its own thread: offered,
     * downloading with a percentage, waiting for the game to close, or failed with a retry. It
     * sits left of the search field and hides with it while a server page is open.
     */
    private int settingsX() {
        return fieldX - 4 - BAR_CONTROL_H;
    }

    private int filterX() {
        return settingsX() - 4 - BAR_CONTROL_H;
    }

    private void refreshUpdateButton() {
        if (settingsButton != null) {
            settingsButton.visible = !detail.active();
        }
        if (filterButton != null) {
            filterButton.visible = !detail.active();
            TelemetryConfig fc = Telemetry.config();
            filterButton.badge = Browse.active(fc) || fc.showCracked;
        }
        if (updateButton == null) {
            return;
        }
        Updater.State st = Updater.state();
        boolean show = !detail.active() && switch (st) {
            case AVAILABLE, DOWNLOADING, READY, FAILED -> true;
            default -> false;
        };
        updateButton.visible = show;
        if (!show) {
            return;
        }
        String label = switch (st) {
            case AVAILABLE -> "Update to " + Updater.offeredVersion();
            case DOWNLOADING -> "Updating " + Updater.progress() + "%";
            case READY -> "Restart to update";
            default -> "Update failed";
        };
        if (st == shownUpdateState && label.equals(shownUpdateLabel)) {
            return;
        }
        shownUpdateState = st;
        shownUpdateLabel = label;
        // Too little room beside the title for the full wording: fall back to one word.
        int titleEnd = GRID_PAD + BAR_CONTROL_H + 8 + Math.round(font.width(Component.literal("Discovery").withStyle(ChatFormatting.BOLD)) * TITLE_SCALE);
        int w = font.width(label) + 16;
        if (filterX() - 6 - w < titleEnd + 8) {
            label = st == Updater.State.DOWNLOADING ? Updater.progress() + "%" : "Update";
            w = font.width(label) + 16;
        }
        updateButton.setMessage(Component.literal(label));
        updateButton.setWidth(w);
        updateButton.setX(filterX() - 6 - w);
        updateButton.setY(BAR_Y);
        updateButton.active = st == Updater.State.AVAILABLE || st == Updater.State.FAILED;
        updateButton.setTooltip(Tooltip.create(Component.literal(switch (st) {
            case AVAILABLE -> "Discovery " + Updater.offeredVersion() + " is out on Modrinth. Click to download it; it installs when you close Minecraft.";
            case DOWNLOADING -> "Downloading Discovery " + Updater.offeredVersion() + " from Modrinth.";
            case READY -> "Discovery " + Updater.offeredVersion() + " is downloaded and checked. It installs when you close Minecraft.";
            default -> "The update didn't download: " + Updater.error() + ". Click to try again.";
        })));
    }

    @Override
    public void removed() {
        pinger.removeAll();
        if (closed) {
            return;
        }
        closed = true;
        flushSearch(null);
        for (Row row : rows) {
            Integer maxPos = reached.get(row.id());
            if (maxPos != null) {
                int pages = (row.source().nextOffset() + ServerListApi.ROW_PAGE - 1) / ServerListApi.ROW_PAGE;
                Telemetry.event("row_reach", "row", row.id(), "pages", pages, "maxPos", maxPos);
            }
        }
        Telemetry.event("page_close", "duration", (System.currentTimeMillis() - openedAt) / 1000,
                "rows", rows.size(), "reason", closeReason == null ? "other" : closeReason);
        closeReason = null;
    }

    @Override
    public void onClose() {
        // Esc and Back leave See all first, back to the page it came from.
        if (seeAll && !detail.active()) {
            leaveSeeAll();
            return;
        }
        if (closeReason == null) {
            closeReason = "back";
        }
        // The title screen only fades in once, at launch, but if this page was opened before
        // that fade had run (the launch-time button click, or the dev auto-open) it would run
        // on the way back, two seconds of blank. It has been seen; skip it.
        if (parent instanceof net.minecraft.client.gui.screens.TitleScreen title) {
            ((cc.serverlist.featured.mixin.TitleScreenAccessor) title).serverlist$setFading(false);
        }
        Compat.setScreen(minecraft, parent);
    }

    // ------------------------------------------------------------------
    // Data
    // ------------------------------------------------------------------

    private void load(boolean force) {
        loading = true;
        error = null;
        updateButtons();
        long startedAt = System.currentTimeMillis();
        boolean cached = !force && ServerListApi.featured(false).isDone();
        ServerListApi.featured(force).whenComplete((page, err) -> minecraft.execute(() -> {
            loading = false;
            if (err != null) {
                Throwable cause = err.getCause() != null ? err.getCause() : err;
                error = cause.getMessage() == null ? cause.toString() : cause.getMessage();
                FeaturedClient.LOGGER.warn("Featured servers fetch failed: {}", error);
                Telemetry.event("error", "kind", "featured_fetch", "status", httpStatus(error));
            } else {
                featured = page;
                recompute();
                pageOpened(System.currentTimeMillis() - startedAt, cached);
            }
            updateButtons();
        }));
    }

    /**
     * The list a query is matched against. The featured page only carries the servers it
     * shows, so the first search fetches the whole tracker list behind it and re-runs the
     * query when it lands; until then, and if it fails, the featured servers are what there is.
     * A server in both comes from the featured page, which knows its tags and art.
     */
    private List<TrackedServer> searchPool() {
        if (searchable != null) {
            return searchable;
        }
        if (featured == null) {
            return List.of();
        }
        ServerListApi.servers(false).whenComplete((list, err) -> minecraft.execute(() -> {
            if (err != null) {
                Throwable cause = err.getCause() != null ? err.getCause() : err;
                Telemetry.event("error", "kind", "search_fetch", "status", httpStatus(cause.getMessage()));
            }
            if (err != null || featured == null) {
                return;
            }
            Map<String, TrackedServer> rich = new HashMap<>();
            for (TrackedServer s : featured.servers()) {
                rich.put(s.slug(), s);
            }
            List<TrackedServer> merged = new ArrayList<>(list.size());
            for (TrackedServer s : list) {
                merged.add(rich.getOrDefault(s.slug(), s));
            }
            searchable = merged;
            recompute();
        }));
        return featured.servers();
    }

    /**
     * Appends the next page of a row, once, and rebuilds the strips when it lands. A page that
     * fails is simply asked for again the next time the strip nears its end.
     */
    private void loadMore(Featured.Row row) {
        if (row.loading || row.exhausted() || Util.getMillis() < row.retryAt) {
            return;
        }
        row.loading = true;
        ServerListApi.rowPage(row.id(), row.nextOffset()).whenComplete((page, err) -> minecraft.execute(() -> {
            row.loading = false;
            if (err != null) {
                Throwable cause = err.getCause() != null ? err.getCause() : err;
                FeaturedClient.LOGGER.warn("Row page {}@{} failed: {}", row.id(), row.nextOffset(), cause.toString());
                Telemetry.event("error", "kind", "row_fetch", "status", httpStatus(cause.getMessage()));
                row.retryAt = Util.getMillis() + 10_000;
                return;
            }
            row.append(page);
            FeaturedClient.LOGGER.debug("Row {}: +{} cards, {} of {}", row.id(), page.size(), row.servers().size(), row.total());
            if (query.isBlank()) {
                recompute();
            }
        }));
    }

    /**
     * "Recently played": the servers this player has logged into, most recent first, as a strip
     * ahead of the API's rows. Tracked ones use the tracker's record (art, tags, count); the
     * rest get a stand-in the live ping fills. Null when there is nothing to show yet.
     */
    private Row recentRow(Map<String, TrackedServer> known) {
        List<RecentServers.Entry> recent = RecentServers.list();
        if (recent.isEmpty()) {
            return null;
        }
        List<TrackedServer> servers = new ArrayList<>();
        boolean unresolved = false;
        long seconds = 0;
        for (RecentServers.Entry e : recent) {
            TrackedServer s = known.get(e.address);
            if (s == null) {
                unresolved = true;
                s = RecentServers.standIn(e);
            }
            servers.add(s);
            seconds += e.seconds;
            if (servers.size() >= 20) {
                break;
            }
        }
        // Names for the untracked ones may be in the full list; ask for it once.
        if (unresolved && searchable == null) {
            searchPool();
        }
        String subtitle = "last on " + RecentServers.ago(recent.get(0).lastAt)
                + (seconds >= 60 ? " \u00b7 " + RecentServers.playtime(seconds) + " played" : "");
        return Row.of(Featured.Row.local("recent", "Jump back in", subtitle, servers));
    }

    /**
     * "Favourites": the servers starred on the page, most recently starred first, in the same
     * short card as Recently played and resolved the same way. Null when nothing is starred.
     */
    private Row favouritesRow(Map<String, TrackedServer> known) {
        List<Favourites.Entry> favourites = Favourites.list();
        if (favourites.isEmpty()) {
            return null;
        }
        List<TrackedServer> servers = new ArrayList<>();
        boolean unresolved = false;
        for (Favourites.Entry e : favourites) {
            TrackedServer s = known.get(e.address);
            if (s == null) {
                unresolved = true;
                s = RecentServers.standIn(e.address, e.name);
            }
            servers.add(s);
        }
        if (unresolved && searchable == null) {
            searchPool();
        }
        String subtitle = servers.size() == 1 ? "1 server starred" : servers.size() + " servers starred";
        return Row.of(Featured.Row.local("favourites", "Favourites", subtitle, servers));
    }

    /**
     * "Cracked servers": the busiest servers the tracker found let anyone in without a Mojang
     * account. They only come with the full list, so the first time this asks for it and the
     * strip appears when it lands. Null until then, or when there are none.
     */
    private Row crackedRow() {
        if (searchable == null) {
            searchPool();
            return null;
        }
        List<TrackedServer> servers = new ArrayList<>();
        int total = 0;
        for (TrackedServer s : searchable) {
            if (s.cracked()) {
                total++;
                if (servers.size() < CRACKED_ROW_LIMIT) {
                    servers.add(s);
                }
            }
        }
        if (servers.isEmpty()) {
            return null;
        }
        String subtitle = "no Mojang account needed Â· " + fmt(total) + " online";
        return Row.of(Featured.Row.local("cracked", "Cracked servers", subtitle, servers));
    }

    /** Every server record in hand, by normalised address: the page first, then the full list. */
    private Map<String, TrackedServer> knownByAddress() {
        Map<String, TrackedServer> known = new HashMap<>();
        if (featured != null) {
            if (featured.hero() != null) {
                known.put(ServerListApi.normalizeAddress(featured.hero().address()), featured.hero());
            }
            for (TrackedServer s : featured.servers()) {
                known.putIfAbsent(ServerListApi.normalizeAddress(s.address()), s);
            }
            for (Featured.Row r : featured.rows()) {
                for (TrackedServer s : r.servers()) {
                    known.putIfAbsent(ServerListApi.normalizeAddress(s.address()), s);
                }
            }
        }
        if (searchable != null) {
            for (TrackedServer s : searchable) {
                known.putIfAbsent(ServerListApi.normalizeAddress(s.address()), s);
            }
        }
        return known;
    }

    /**
     * Rebuilds the page â€” a grid of search results or of the filtered full list, or the hero
     * and every strip â€” keeping the selection. Sort &amp; filter apply to the grid either way
     * (see {@link Browse}); with them at their defaults and nothing typed it is the strips.
     */
    private void recompute() {
        String q = query.trim().toLowerCase(Locale.ROOT);
        TelemetryConfig c = Telemetry.config();
        hero = null;
        browsing = q.isEmpty() && (Browse.active(c) || seeAll);
        if (!q.isEmpty() || browsing) {
            List<TrackedServer> matched = new ArrayList<>();
            for (TrackedServer s : searchPool()) {
                if (!Browse.keep(s, c)) {
                    continue;
                }
                if (q.isEmpty()
                        || s.name().toLowerCase(Locale.ROOT).contains(q)
                        || s.host().toLowerCase(Locale.ROOT).contains(q)
                        || s.slug().contains(q)
                        || s.motd().toLowerCase(Locale.ROOT).contains(q)) {
                    matched.add(s);
                }
            }
            List<TrackedServer> sorted = Browse.sort(matched, c);
            // A plain search keeps its short list of the best matches; sorted or filtered, the
            // player is browsing and gets far more.
            int limit = Browse.active(c) || seeAll ? BROWSE_LIMIT : SEARCH_LIMIT;
            shownTotal = sorted.size();
            shown = sorted.size() > limit ? List.copyOf(sorted.subList(0, limit)) : sorted;
            rows = List.of();
            if (!q.isEmpty()) {
                searchLen = q.length();
                searchResults = shown.size();
            }
        } else {
            shown = List.of();
            shownTotal = 0;
            List<Row> built = new ArrayList<>();
            Map<String, TrackedServer> known = knownByAddress();
            // Either local row can be turned off in the settings.
            Row recent = Telemetry.config().showRecent ? recentRow(known) : null;
            if (recent != null) {
                built.add(recent);
            }
            Row favourites = Telemetry.config().showFavourites ? favouritesRow(known) : null;
            // Favourites go under Top servers, not above it: on a normal window the first
            // screen should show servers, not two rows of places the player already knows.
            int favouritesAt = built.size();
            // Cracked servers, when the player has asked for them: a strip of their own after
            // Top and Rising, from the full list (the API's rows never carry them).
            Row cracked = c.showCracked ? crackedRow() : null;
            int crackedAt = built.size();
            if (featured != null) {
                // The hero needs the width; on a narrow window it takes its place in the top
                // row instead, where the API left a gap for it.
                // No hero any more: the featured server is the first tile of Top servers,
                // marked Featured the way Roblox marks a sponsored game.
                hero = null;
                for (Featured.Row r : featured.rows()) {
                    Row row = Row.of(r);
                    if ("top".equals(r.id())) {
                        // The API's Top row is the raw count. Here it is every server the page
                        // knows, ranked by Browse's Top score (size, momentum, fit), with the
                        // featured server in front as its own big tile.
                        TrackedServer pick = featured.hero();
                        Map<String, TrackedServer> pool = new LinkedHashMap<>();
                        for (TrackedServer s : r.servers()) {
                            pool.putIfAbsent(s.slug(), s);
                        }
                        for (TrackedServer s : featured.servers()) {
                            pool.putIfAbsent(s.slug(), s);
                        }
                        List<TrackedServer> ranked = new ArrayList<>(pool.size() + 1);
                        if (pick != null) {
                            ranked.add(pick);
                        }
                        for (TrackedServer s : Browse.sort(new ArrayList<>(pool.values()), c)) {
                            if (Browse.keep(s, c) && (pick == null || !s.slug().equals(pick.slug()))) {
                                ranked.add(s);
                            }
                        }
                        row = new Row(row.id(), row.label(), row.subtitle(), ranked, r);
                        topLayout(pick != null);
                    }
                    built.add(row);
                    if ("top".equals(r.id())) {
                        favouritesAt = built.size();
                    }
                    if (cracked != null && ("rising".equals(r.id()) || "top".equals(r.id()))) {
                        crackedAt = built.size();
                    }
                }
            }
            if (cracked != null) {
                built.add(Math.min(crackedAt, built.size()), cracked);
            }
            if (favourites != null) {
                built.add(Math.min(favouritesAt, built.size()), favourites);
            }
            rows = spreadOut(built);
        }
        if (selected != null && rowOf(selected) < 0 && !shown.contains(selected) && selected != hero) {
            selected = null;
        }
        scroll = Math.min(scroll, maxScroll());
        for (Row r : rows) {
            StripScroll ss = scrollOf(r);
            ss.target = Math.min(ss.target, rowMaxScroll(r));
            ss.current = Math.min(ss.current, rowMaxScroll(r));
        }
        updateButtons();
    }

    /** How far into a row {@link #spreadOut} looks for servers to bring forward: the API's first page. */
    private static final int SPREAD_LOOK = 20;

    /**
     * The API ranks every genre row by players, so the big networks that list several genres
     * (Hypixel, Complex, Minehut...) would open nearly every strip. Going down the page, the
     * cards a row shows before any scrolling go to servers no row above has already opened
     * with; the repeats keep their place after those, so nothing leaves a row. A server whose
     * main tag has a row further down waits for that one, so 2b2t opens Anarchy, not SMP.
     * Top servers is left as ranked, and Jump back in and Favourites are the player's own.
     */
    private List<Row> spreadOut(List<Row> built) {
        Set<String> led = new HashSet<>();
        Map<String, Integer> home = new HashMap<>();
        List<Row> out = new ArrayList<>(built.size());
        for (int r = 0; r < built.size(); r++) {
            Row row = built.get(r);
            if (row.compact()) {
                out.add(row);
                continue;
            }
            List<TrackedServer> servers = row.servers();
            if (row.grid()) {
                int rest = topSpan > 0 && topSpan < columns ? columns - topSpan : columns;
                int shown = Math.min(servers.size(), (topSpan > 0 ? 1 : 0) + rest * 2);
                for (int i = 0; i < shown; i++) {
                    led.add(servers.get(i).slug());
                }
                out.add(row);
                continue;
            }
            int lead = Math.max(1, columns);
            int look = Math.min(servers.size(), SPREAD_LOOK);
            List<TrackedServer> front = new ArrayList<>(lead);
            Set<String> moved = new HashSet<>();
            for (int i = 0; i < look && front.size() < lead; i++) {
                TrackedServer s = servers.get(i);
                if (!led.contains(s.slug()) && home.computeIfAbsent(s.slug(), k -> homeRow(s, built)) <= r) {
                    front.add(s);
                    moved.add(s.slug());
                }
            }
            List<TrackedServer> order = new ArrayList<>(servers.size());
            order.addAll(front);
            for (TrackedServer s : servers) {
                if (!moved.contains(s.slug())) {
                    order.add(s);
                }
            }
            for (int i = 0; i < Math.min(lead, order.size()); i++) {
                led.add(order.get(i).slug());
            }
            out.add(new Row(row.id(), row.label(), row.subtitle(), order, row.source()));
        }
        return out;
    }

    /** Tags whose genre row doesn't name them in its id, title or subtitle. */
    private static final Map<String, String> TAG_ROWS = Map.of(
            "mmorpg", "rpg", "roleplay", "rpg", "adventure", "rpg",
            "kitpvp", "pvp", "crystal pvp", "pvp", "uhc", "pvp");

    /**
     * The index of the genre row for a server's first tag, which is its main one: the row with
     * that id, else the first whose title or subtitle names it. -1 when none on the page does.
     */
    private static int homeRow(TrackedServer s, List<Row> rows) {
        if (s.tags() == null || s.tags().isEmpty()) {
            return -1;
        }
        String tag = s.tags().get(0).toLowerCase(Locale.ROOT);
        String id = TAG_ROWS.getOrDefault(tag, tag);
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).id().equals(id)) {
                return i;
            }
        }
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (row.compact() || row.grid()) {
                continue;
            }
            String words = (row.label().getString() + ", " + (row.subtitle() == null ? "" : row.subtitle())).toLowerCase(Locale.ROOT);
            if (java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(tag) + "\\b").matcher(words).find()) {
                return i;
            }
        }
        return -1;
    }

    /** Which strip holds this server, or -1 (the hero and search results are not in a strip). */
    private int rowOf(TrackedServer s) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).servers().contains(s)) {
                return i;
            }
        }
        return -1;
    }

    private StripScroll scrollOf(Row row) {
        return stripScroll.computeIfAbsent(row.id(), k -> new StripScroll());
    }

    private int rowMaxScroll(Row r) {
        if (r.grid()) {
            return 0;
        }
        int content = r.servers().size() * r.pitch() - GAP;
        return Math.max(0, content - innerW);
    }

    /** The page's own widgets step aside while a server page is open. */
    void detailChanged(boolean open) {
        search.visible = !open;
        if (open) {
            clearFocus();
        }
        updateButtons();
    }

    /** A link from a server page: the same confirmation vanilla asks for. */
    void openLink(String url) {
        // Only web links: a listing's URL must never open a file or hand off to another app.
        String scheme = url.contains(":") ? url.substring(0, url.indexOf(':')).toLowerCase(java.util.Locale.ROOT) : "";
        if (!scheme.equals("https") && !scheme.equals("http")) {
            return;
        }
        Screen self = this;
        Compat.confirmLink(minecraft, self, url);
    }

    private void updateButtons() {
        if (retryButton != null) {
            retryButton.visible = error != null && !loading && shown.isEmpty() && rows.isEmpty();
        }
    }

    /** The live-ping record for a card, started on first sight exactly as the vanilla list does it. */
    ServerData liveFor(TrackedServer s) {
        ServerData data = live.get(s.slug());
        if (data == null) {
            data = new ServerData(s.name(), s.address(), ServerData.Type.OTHER);
            live.put(s.slug(), data);
        }
        if (data.state() == ServerData.State.INITIAL) {
            data.setState(ServerData.State.PINGING);
            data.motd = CommonComponents.EMPTY;
            data.status = CommonComponents.EMPTY;
            ServerData target = data;
            PING_POOL.submit(() -> {
                try {
                    Compat.pingServer(minecraft, pinger, target, () -> {
                    }, () -> {
                        target.setState(
                                target.protocol == SharedConstants.getCurrentVersion().protocolVersion()
                                        ? ServerData.State.SUCCESSFUL
                                        : ServerData.State.INCOMPATIBLE);
                        if (target.ping > 0) {
                            Telemetry.event("ping", "slug", RecentServers.reportable(s), "ms", target.ping);
                        }
                    });
                } catch (UnknownHostException e) {
                    target.setState(ServerData.State.UNREACHABLE);
                    target.motd = Component.translatable("multiplayer.status.cannot_resolve").withStyle(ChatFormatting.RED);
                } catch (Exception e) {
                    target.setState(ServerData.State.UNREACHABLE);
                    target.motd = Component.translatable("multiplayer.status.cannot_connect").withStyle(ChatFormatting.RED);
                }
            });
        }
        return data;
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private void join(TrackedServer s) {
        Card from = null;
        for (Card c : cards) {
            if (c.server() == s) {
                from = c;
                break;
            }
        }
        join(s, from);
    }

    void join(TrackedServer s, Card from) {
        ServerData data = liveFor(s);
        closeReason = "join";
        flushSearch(from != null && "search".equals(from.row()) ? RecentServers.reportable(s) : null);
        Telemetry.pageJoin(RecentServers.reportable(s), from != null ? from.row() : null, from != null ? from.pos() : -1, from != null && from.hero());
        leaveCurrentWorld();
        ConnectScreen.startConnecting(this, minecraft, ServerAddress.parseString(s.address()), data, false, null);
    }

    /** Whether the star is lit: the server is in the player's Favourites. */
    boolean isStarred(TrackedServer s) {
        return Favourites.contains(s.address());
    }

    /**
     * Joining from the pause menu's Discovery: leave the server the player is on first, the way
     * the pause menu's Disconnect does, rather than staying logged in there until the new login
     * finishes (which is what vanilla's connect screen does, and what other players on the old
     * server saw as the player standing still). The page was opened over that world's pause menu,
     * which is gone with it, so Back goes to the title screen from here on.
     */
    private void leaveCurrentWorld() {
        if (minecraft.level == null) {
            return;
        }
        Telemetry.switchingServers();
        boolean local = minecraft.isLocalServer();
        minecraft.level.disconnect(ClientLevel.DEFAULT_QUIT_MESSAGE);
        if (local) {
            minecraft.disconnectWithSavingScreen();
        } else {
            minecraft.disconnectWithProgressScreen();
        }
        parent = new TitleScreen();
    }

    /**
     * The star: adds the server to Favourites, and to the player's own multiplayer list unless
     * it is already there (by site, so any subdomain they saved counts). Un-starring takes it out
     * of Favourites, and out of the list only if the star was what put it there.
     */
    void toggleSaved(TrackedServer s) {
        ServerList servers = new ServerList(minecraft);
        servers.load();
        String key = ServerListApi.siteKey(s.address());
        List<ServerData> existing = new ArrayList<>();
        for (int i = 0; i < servers.size(); i++) {
            ServerData d = servers.get(i);
            if (key != null && key.equals(ServerListApi.siteKey(d.ip))) {
                existing.add(d);
            }
        }
        Favourites.Entry removed = Favourites.remove(s.address());
        if (removed != null) {
            if (removed.addedToList && !existing.isEmpty()) {
                for (ServerData d : existing) {
                    servers.remove(d);
                }
                saved.remove(key);
                servers.save();
            }
        } else {
            boolean added = false;
            if (existing.isEmpty()) {
                ServerData copy = new ServerData(s.name(), s.address(), ServerData.Type.OTHER);
                ServerData pinged = live.get(s.slug());
                if (pinged != null && pinged.getIconBytes() != null) {
                    copy.setIconBytes(pinged.getIconBytes());
                }
                servers.add(copy, false);
                saved.add(key);
                servers.save();
                added = true;
            }
            Favourites.add(s.address(), RecentServers.reportable(s), s.name(), added);
        }
        // The Favourites row appears, grows or goes; keep what is on screen where it was.
        int before = maxScroll() + innerH;
        boolean hadRow = rows.stream().anyMatch(r -> "favourites".equals(r.id()));
        recompute();
        boolean hasRow = rows.stream().anyMatch(r -> "favourites".equals(r.id()));
        if (hadRow != hasRow && scroll > 0 && shown.isEmpty()) {
            scroll = clamp(scroll + (maxScroll() + innerH - before), 0, maxScroll());
        }
    }

    void click() {
        AbstractWidget.playButtonClickSound(minecraft.getSoundManager());
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = cx(event);
        double my = cy(event);
        if (overNotice(mx, my)) {
            super.mouseClicked(event, doubleClick); // its buttons
            return true;
        }
        if (friendsPanel.open()) {
            // Inside, its buttons; anywhere else closes it, and that is all the click does.
            if (friendsPanel.contains(mx, my)) {
                if (event.button() == 0) {
                    friendsPanel.click(mx, my);
                }
            } else {
                friendsPanel.close();
            }
            return true;
        }
        if (event.button() == 0 && inside(mx, my, meX(), BAR_Y, BAR_CONTROL_H, BAR_CONTROL_H)) {
            click();
            closeReason = "settings";
            Compat.setScreen(minecraft, new SettingsScreen(this, SettingsScreen.Tab.FRIENDS));
            return true;
        }
        if (event.button() == 0 && inside(mx, my, fieldX, BAR_Y, fieldW, BAR_CONTROL_H)) {
            if (!query.isEmpty() && inside(mx, my, clearX(), BAR_Y, SEARCH_CLEAR_W, BAR_CONTROL_H)) {
                click();
                search.setValue("");
                return true;
            }
            if (!super.mouseClicked(event, doubleClick)) {
                setFocused(search);
            }
            return true;
        }
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (detail.active()) {
            return detail.mouseClicked(mx, my, event.button());
        }
        if (event.button() == 1 && shown.isEmpty()) {
            // Right-click a friend: their menu.
            for (FriendHit f : friendHits) {
                if (f.id() != null && inside(mx, my, f.x(), f.y(), f.w(), f.h())) {
                    click();
                    friendsPanel.openMenu(f.id());
                    return true;
                }
            }
        }
        if (event.button() != 0) {
            return false;
        }
        clearFocus();
        if (!inside(mx, my, gridX, gridY, gridW, gridH)) {
            return false;
        }

        for (Strip strip : strips) {
            if (strip.maxScroll() <= 0) {
                continue;
            }
            StripScroll ss = scrollOf(rows.get(strip.rowIndex()));
            int pitch = rows.get(strip.rowIndex()).pitch();
            int page = Math.max(1, innerW / pitch) * pitch;
            if (inside(mx, my, strip.backX(), strip.arrowY(), ARROW_W, ARROW_H) && ss.target > 0) {
                click();
                ss.target = snap(ss.target - page, strip.maxScroll(), pitch);
                return true;
            }
            if (inside(mx, my, strip.forwardX(), strip.arrowY(), ARROW_W, ARROW_H) && ss.target < strip.maxScroll()) {
                click();
                ss.target = snap(ss.target + page, strip.maxScroll(), pitch);
                return true;
            }
        }

        if (mx >= innerX + innerW) {
            // The scrollbar: the thumb follows the mouse from where it was grabbed.
            if (maxScroll() > 0) {
                press = new Press(mx, my, null, -1, scroll, 0f, true);
            }
            return true;
        }
        if (mx < innerX) {
            return true;
        }
        // Cards act when the button comes back up over the same spot (see mouseReleased), so a
        // press that turns into a drag scrolls instead of opening or joining anything.
        int stripIndex = -1;
        for (Strip strip : strips) {
            if (strip.contains(mx, my)) {
                stripIndex = strip.rowIndex();
            }
        }
        float stripStart = stripIndex >= 0 ? scrollOf(rows.get(stripIndex)).current : 0f;
        String target = hitAt(mx, my);
        press = new Press(mx, my, target, stripIndex, scroll, stripStart, false);
        if (target != null) {
            return true;
        }
        if (clearFiltersW > 0 && !shown.isEmpty() && inside(mx, my, clearFiltersX, clearFiltersY - 2, clearFiltersW, 12)) {
            click();
            if (Browse.active(Telemetry.config())) {
                Browse.reset(Telemetry.config());
                Telemetry.saveConfig();
            }
            seeAll = false;
            scroll = 0;
            recompute();
            return true;
        }
        if (seeAllW > 0 && shown.isEmpty() && inside(mx, my, seeAllX, seeAllY - 2, seeAllW, 12)) {
            click();
            seeAll = true;
            scroll = 0;
            recompute();
            return true;
        }
        return true;
    }

    /**
     * What a click at this point would do, as a key that is equal for the same action on the
     * same card: {@code join|<row>|<pos>|<slug>}, {@code star|...} or {@code open|...}; null for
     * none.
     */
    private String hitAt(double mx, double my) {
        if (shown.isEmpty()) {
            for (FriendHit f : friendHits) {
                if (inside(mx, my, f.x(), f.y(), f.w(), f.h())) {
                    return "friend|" + (f.id() == null ? "add" : f.id());
                }
            }
        }
        Card c = cardAt(mx, my);
        if (c == null) {
            return null;
        }
        String where = "|" + c.row() + "|" + c.pos() + "|" + c.server().slug();
        if (inside(mx, my, c.joinX(), c.joinY(), JOIN_W, JOIN_H)) {
            return "join" + where;
        }
        if (!c.compact() && inside(mx, my, c.starX(), c.starY(), STAR_HIT, STAR_HIT)) {
            return "star" + where;
        }
        return "open" + where;
    }

    private Card cardAt(double mx, double my) {
        if (mx < innerX || mx >= innerX + innerW || !inside(mx, my, gridX, gridY, gridW, gridH)) {
            return null;
        }
        for (Card c : cards) {
            if (inside(mx, my, c.x(), c.y(), c.w(), c.h())) {
                return c;
            }
        }
        return null;
    }

    /** Carries out a {@link #hitAt} key on the card now under the mouse. */
    private void activate(String hit, double mx, double my) {
        if (hit.startsWith("friend|")) {
            click();
            String which = hit.substring(7);
            if (which.equals("add")) {
                friendsPanel.openAdd();
                return;
            }
            // A friend on a listed server: that server's page. Anywhere else: their menu.
            UUID id = UUID.fromString(which);
            for (Friends.Friend f : Friends.snapshot().friends()) {
                if (f.id().equals(id)) {
                    TrackedServer on = f.on() == null ? null : serverFor(f.on());
                    FriendHit tile = null;
                    for (FriendHit h : friendHits) {
                        if (id.equals(h.id())) {
                            tile = h;
                        }
                    }
                    if (on != null && tile != null) {
                        openServer(on, tile);
                    } else {
                        friendsPanel.openMenu(id);
                    }
                }
            }
            return;
        }
        Card c = cardAt(mx, my);
        if (c == null) {
            return;
        }
        click();
        if (hit.startsWith("join|")) {
            join(c.server(), c);
        } else if (hit.startsWith("star|")) {
            toggleSaved(c.server());
        } else {
            if (selected != c.server()) {
                Telemetry.event("select", "slug", RecentServers.reportable(c.server()), "row", c.row(), "pos", c.pos(), "hero", c.hero());
            }
            selected = c.server();
            detail.open(c);
        }
    }

    /**
     * Dragging scrolls: the page up and down, a strip sideways when the drag starts out more
     * sideways than not on a strip that has somewhere to go, and the scrollbar's thumb. It only
     * counts as a drag past a few pixels, so a slightly shaky click is still a click.
     */
    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (detail.active()) {
            return detail.mouseDragged(cx(event), cy(event), event.button()) || super.mouseDragged(event, dx, dy);
        }
        if (press == null || event.button() != 0) {
            return super.mouseDragged(event, dx, dy);
        }
        dragTo(cx(event), cy(event));
        return true;
    }

    /** Scrolls for the mouse being at (mx, my) with the button held since {@link #press}. */
    private void dragTo(double mx, double my) {
        double ox = mx - press.x;
        double oy = my - press.y;
        if (!press.dragging) {
            if (!press.scrollbar && ox * ox + oy * oy < DRAG_SLOP * DRAG_SLOP) {
                return;
            }
            press.dragging = true;
            press.sideways = !press.scrollbar && press.strip >= 0 && Math.abs(ox) > Math.abs(oy)
                    && rowMaxScroll(rows.get(press.strip)) > 0;
        }
        long now = Util.getMillis();
        if (press.sideways) {
            Row row = rows.get(press.strip);
            StripScroll ss = scrollOf(row);
            float to = clamp((int) Math.round(press.stripStart - ox), 0, rowMaxScroll(row));
            ss.current = to;
            ss.target = to;
            ss.pending = 0;
            // Pixels per millisecond, smoothed, for the fling on release.
            long dt = Math.max(1, now - press.lastAt);
            press.velocity = press.velocity * 0.6 + ((mx - press.lastX) / dt) * 0.4;
        } else if (press.scrollbar) {
            int max = maxScroll();
            int thumbH = Math.max(16, innerH * innerH / (max + innerH));
            int travel = Math.max(1, innerH - thumbH);
            scroll = clamp((int) Math.round(press.pageStart + oy * max / travel), 0, max);
        } else {
            scroll = clamp((int) Math.round(press.pageStart - oy), 0, maxScroll());
        }
        press.lastX = mx;
        press.lastAt = now;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        boolean handled = super.mouseReleased(event);
        if (detail.active()) {
            press = null;
            return detail.mouseReleased(cx(event), cy(event), event.button()) || handled;
        }
        if (press == null || event.button() != 0) {
            press = null;
            return handled;
        }
        // Movement reaches the screen once a frame, the release straight away: at a low frame
        // rate the whole drag can land here first. Catch up, so a press that moved is a drag.
        if (!press.dragging) {
            dragTo(cx(event), cy(event));
        }
        Press p = press;
        press = null;
        if (p.dragging) {
            if (p.sideways) {
                Row row = rows.get(p.strip);
                StripScroll ss = scrollOf(row);
                // A drag still moving when let go carries on a little way, then lands on a card edge.
                boolean moving = Util.getMillis() - p.lastAt < 80;
                float fling = moving ? (float) (-p.velocity * 180) : 0f;
                ss.target = snap(ss.current + fling, rowMaxScroll(row), row.pitch());
            }
            return true;
        }
        if (p.target != null && p.target.equals(hitAt(cx(event), cy(event)))) {
            activate(p.target, cx(event), cy(event));
            return true;
        }
        return handled;
    }

    /**
     * The wheel scrolls the page; over a strip, a sideways wheel (or shift + wheel) scrolls the
     * strip instead, which is how every trackpad and tilt-wheel user will try it first.
     *
     * <p>A trackpad reports a finger that is mostly moving down as a little sideways too, so
     * it is the larger axis that decides, and a strip only moves once a whole notch's worth of
     * sideways has built up â€” a mouse's tilt wheel sends exactly one per click.
     */
    @Override
    public boolean mouseScrolled(double screenX, double screenY, double sx, double sy) {
        double mx = contentX(screenX, screenY);
        double my = contentY(screenY);
        if (friendsPanel.open()) {
            friendsPanel.close();
        }
        if (detail.active()) {
            return detail.mouseScrolled(mx, my, sy);
        }
        if (!inside(mx, my, gridX, gridY, gridW, gridH)) {
            return super.mouseScrolled(screenX, screenY, sx, sy);
        }
        boolean shift = Compat.shiftDown(minecraft);
        if (shift || Math.abs(sx) > Math.abs(sy)) {
            for (Strip strip : strips) {
                if (strip.contains(mx, my)) {
                    StripScroll ss = scrollOf(rows.get(strip.rowIndex()));
                    ss.pending += shift && sx == 0 ? -sy : sx;
                    // One card per notch, always landing on a card edge.
                    while (Math.abs(ss.pending) >= 1) {
                        int pitch = rows.get(strip.rowIndex()).pitch();
                        int step = ss.pending > 0 ? pitch : -pitch;
                        ss.pending -= Math.signum(ss.pending);
                        ss.target = snap(ss.target + step, strip.maxScroll(), pitch);
                    }
                    return true;
                }
            }
        }
        if (sy != 0) {
            scroll = clamp((int) Math.round(scroll - sy * 40), 0, maxScroll());
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        boolean typing = getFocused() instanceof EditBox;
        int key = event.key();
        if (friendsPanel.open() && friendsPanel.keyPressed(key)) {
            return true;
        }
        if (detail.active()) {
            return detail.keyPressed(key) || super.keyPressed(event);
        }
        if (key == InputConstants.KEY_ESCAPE && !typing) {
            closeReason = "esc";
        }
        if (!typing) {
            if ((key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) && selected != null) {
                join(selected);
                return true;
            }
            if (!shown.isEmpty()) {
                int step = switch (key) {
                    case InputConstants.KEY_RIGHT -> 1;
                    case InputConstants.KEY_LEFT -> -1;
                    case InputConstants.KEY_DOWN -> columns;
                    case InputConstants.KEY_UP -> -columns;
                    default -> 0;
                };
                if (step != 0) {
                    int i = selected == null ? -1 : shown.indexOf(selected);
                    i = i < 0 ? 0 : clamp(i + step, 0, shown.size() - 1);
                    selected = shown.get(i);
                    ensureVisibleInGrid(i);
                    return true;
                }
            } else if (!rows.isEmpty() && (key == InputConstants.KEY_RIGHT || key == InputConstants.KEY_LEFT
                    || key == InputConstants.KEY_DOWN || key == InputConstants.KEY_UP)) {
                moveSelection(key);
                return true;
            }
        }
        return super.keyPressed(event);
    }

    /** Arrow keys: left/right along a strip, up/down between strips (the hero counts as one). */
    private void moveSelection(int key) {
        int row = selected == null ? -1 : selected == hero ? -1 : rowOf(selected);
        int index = row < 0 ? 0 : rows.get(row).servers().indexOf(selected);
        if (selected == null) {
            row = hero != null ? -1 : 0;
            index = 0;
        } else if (key == InputConstants.KEY_RIGHT || key == InputConstants.KEY_LEFT) {
            if (row >= 0) {
                index = clamp(index + (key == InputConstants.KEY_RIGHT ? 1 : -1), 0, rows.get(row).servers().size() - 1);
            }
        } else {
            int lowest = rows.size() - 1;
            int first = hero != null ? -1 : 0;
            row = clamp(row + (key == InputConstants.KEY_DOWN ? 1 : -1), first, lowest);
            if (row >= 0) {
                index = clamp(index, 0, rows.get(row).servers().size() - 1);
            }
        }
        selected = row < 0 ? hero : rows.get(row).servers().get(index);
        ensureVisibleInRows(row, index);
    }

    /**
     * Strip positions always land on a card edge, or on the very end, so the first visible card
     * is never a sliver. The end wins when it is within half a card of the nearest edge.
     */
    private float snap(float value, int max, int pitch) {
        float snapped = Math.round(value / pitch) * pitch;
        if (snapped > max - pitch / 2f) {
            snapped = max;
        }
        return clamp(Math.round(snapped), 0, max);
    }

    private void ensureVisibleInRows(int row, int index) {
        int top = 0;
        if (row >= 0) {
            top = heroOffset() + ROW_TITLE_H;
            for (int i = 0; i < row; i++) {
                top += ROW_TITLE_H + rows.get(i).height() + ROW_GAP;
            }
            Row r = rows.get(row);
            if (!r.grid()) {
                StripScroll ss = scrollOf(r);
                int left = index * r.pitch();
                if (left < ss.target) {
                    ss.target = left;
                } else if (left + r.cardW() > ss.target + innerW) {
                    ss.target = left + r.cardW() - innerW;
                }
            }
        }
        int h = row < 0 ? heroH : rows.get(row).height();
        int titleTop = row < 0 ? top : top - ROW_TITLE_H;
        if (titleTop < scroll) {
            scroll = titleTop;
        } else if (top + h > scroll + innerH) {
            scroll = top + h - innerH;
        }
        scroll = clamp(scroll, 0, maxScroll());
    }

    private void ensureVisibleInGrid(int index) {
        int row = index / columns;
        int top = GRID_BAR_H + row * (CARD_H + GAP);
        if (top < scroll) {
            scroll = top;
        } else if (top + CARD_H > scroll + innerH) {
            scroll = top + CARD_H - innerH;
        }
        scroll = clamp(scroll, 0, maxScroll());
    }

    /** What sits above the first row: the friends strip, on the plain page. */
    private int heroOffset() {
        return rows.isEmpty() ? 0 : friendsH() + ROW_GAP;
    }

    /** The strip is only as tall as what is in it: the second line goes when no tile has one. */
    private int friendsH() {
        Friends.Snapshot snap = Friends.snapshot();
        boolean sub = !snap.friends().isEmpty() || !snap.incoming().isEmpty();
        return FRIENDS_NAME_H + (sub ? FRIENDS_SUB_H : 0);
    }

    private int maxScroll() {
        int content;
        if (!shown.isEmpty()) {
            int gridRows = (shown.size() + columns - 1) / columns;
            content = GRID_BAR_H + gridRows * (CARD_H + GAP) - GAP;
        } else {
            content = heroOffset() - ROW_GAP + PAGE_FOOTER_H;
            for (Row r : rows) {
                content += ROW_TITLE_H + r.height() + ROW_GAP;
            }
        }
        return Math.max(0, content - innerH);
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        if (overNotice(mouseX, mouseY)) {
            mouseX = mouseY = -1;
        }
        if (!shown.isEmpty() || detail.active() || rows.isEmpty()) {
            friendsPanel.close();
        }
        int pageX = mouseY < HEADER_H ? -1 : (int) Math.floor(contentX(mouseX, mouseY));
        int pageY = mouseY < HEADER_H ? -1 : (int) Math.floor(contentY(mouseY));
        boolean scaled = contentScale < 0.999f;
        if (scaled) {
            // The margins are the page's own colour, so it reads as one panel and not a box
            // floating over the game behind it.
            g.fill(0, HEADER_H, contentPad + 1, height, PAGE_BG);
            g.fill(width - contentPad - 1, HEADER_H, width, height, PAGE_BG);
            g.pose().pushMatrix();
            g.pose().translate(contentPad, HEADER_H);
            g.pose().scale(contentScale, contentScale);
            g.pose().translate(0, -HEADER_H);
        }
        boolean overPanel = friendsPanel.contains(pageX, pageY);
        drawContent(g, overPanel ? -1 : pageX, overPanel ? -1 : pageY);
        friendsPanel.draw(g, friendHits, pageX, pageY);
        if (scaled) {
            g.pose().popMatrix();
        }
        drawNotice(g);
        drawHeader(g, mouseX, mouseY);
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    /**
     * The title bar: back arrow, the title at row-title size, and the search field with its
     * magnifier and clear cross. The widgets themselves (the arrow button, the text inside the
     * field) draw after this.
     */
    private void drawHeader(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.fill(0, 0, width, HEADER_H, HEADER_BG);
        g.fill(0, HEADER_H - 1, width, HEADER_H, 0xFF000000);
        g.fill(0, HEADER_H - 2, width, HEADER_H - 1, 0xFF1F1F23);

        Component title = Component.literal("Discovery");
        int titleH = Math.round(8 * TITLE_SCALE);
        textScaled(g, title, GRID_PAD + BAR_CONTROL_H + 8, BAR_Y + (BAR_CONTROL_H - titleH) / 2, TITLE_SCALE, WHITE);

        drawMe(g, mouseX, mouseY);
        if (detail.active()) {
            return;
        }
        boolean focused = search.isFocused();
        // A dark rounded field with a thin rim, lighter while typing, and a pixel magnifier.
        boolean fieldHover = inside(mouseX, mouseY, fieldX, BAR_Y, fieldW, BAR_CONTROL_H);
        Round.fill(g, fieldX, BAR_Y, fieldW, BAR_CONTROL_H, RADIUS, focused ? 0xFF8A8A94 : fieldHover ? 0xFF55555E : 0xFF3A3A40);
        Round.fill(g, fieldX + 1, BAR_Y + 1, fieldW - 2, BAR_CONTROL_H - 2, RADIUS - 1, 0xFF0E0E10);
        drawPixels(g, MAGNIFIER, fieldX + 7, BAR_Y + (BAR_CONTROL_H - MAGNIFIER.length) / 2, focused ? WHITE : 0xFF8A8A94);
        if (!query.isEmpty()) {
            boolean hover = inside(mouseX, mouseY, clearX(), BAR_Y, SEARCH_CLEAR_W, BAR_CONTROL_H);
            String cross = "\u00d7";
            g.text(font, cross, clearX() + (SEARCH_CLEAR_W - font.width(cross)) / 2, BAR_Y + (BAR_CONTROL_H - 8) / 2, hover ? WHITE : GRAY);
        }
    }

    /** The status code in a fetch error ("HTTP 503 from â€¦"), or 0 when it never got one. */
    private static int httpStatus(String message) {
        if (message == null) {
            return 0;
        }
        int i = message.indexOf("HTTP ");
        if (i < 0 || i + 8 > message.length()) {
            return 0;
        }
        try {
            return Integer.parseInt(message.substring(i + 5, i + 8));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static final String[] MAGNIFIER = {
            "..####....",
            ".#....#...",
            "#......#..",
            "#......#..",
            "#......#..",
            "#......#..",
            ".#....#...",
            "..####.#..",
            ".......##.",
            "........##",
    };

    /** A little pixel drawing, '#' for a pixel, in one colour. */
    static void drawPixels(GuiGraphicsExtractor g, String[] rows, int x, int y, int color) {
        for (int r = 0; r < rows.length; r++) {
            String line = rows[r];
            for (int c = 0; c < line.length(); c++) {
                if (line.charAt(c) != '#') {
                    continue;
                }
                int end = c;
                while (end < line.length() && line.charAt(end) == '#') {
                    end++;
                }
                g.fill(x + c, y + r, x + end, y + r + 1, color);
                c = end;
            }
        }
    }

    /** The player's own head at the right end of the bar; it opens the Friends settings. */
    private void drawMe(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int x = meX();
        String name = minecraft.getUser().getName();
        boolean hover = inside(mouseX, mouseY, x, BAR_Y, BAR_CONTROL_H, BAR_CONTROL_H);
        RemoteTextures.Tex head = RemoteTextures.get("head:" + name, "https://mc-heads.net/avatar/" + name + "/64");
        if (head != null) {
            g.blit(RenderPipelines.GUI_TEXTURED, head.id(), x, BAR_Y, 0f, 0f, BAR_CONTROL_H, BAR_CONTROL_H, BAR_CONTROL_H, BAR_CONTROL_H);
            Round.mask(g, x, BAR_Y, BAR_CONTROL_H, BAR_CONTROL_H, RADIUS, HEADER_BG);
        } else {
            Round.fill(g, x, BAR_Y, BAR_CONTROL_H, BAR_CONTROL_H, RADIUS, TILE_BG);
        }
        if (hover) {
            Round.outline(g, x, BAR_Y, BAR_CONTROL_H, BAR_CONTROL_H, RADIUS, WHITE);
            g.setTooltipForNextFrame(font, Component.literal(name + ". Click for friends settings."), mouseX, mouseY);
        }
    }

    private int meX() {
        return width - GRID_PAD - BAR_CONTROL_H;
    }

    private int clearX() {
        return fieldX + fieldW - SEARCH_CLEAR_W;
    }

    /** "2,171 servers online": the Top row's length, plus the hero it leaves out. */
    private String onlineCount() {
        if (featured == null) {
            return null;
        }
        for (Featured.Row r : featured.rows()) {
            if ("top".equals(r.id())) {
                return fmt(r.total() + (featured.hero() != null ? 1 : 0)) + " servers online";
            }
        }
        return null;
    }


    private void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (detail.active()) {
            // The rows stay under the page while it fades in and out.
            if (detail.showsRowsBehind()) {
                drawPage(g, -1, -1);
            }
            detail.draw(g, mouseX, mouseY);
            return;
        }
        drawPage(g, mouseX, mouseY);
    }

    private void drawPage(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        // The list background scrolls with the content, as vanilla's does.
        g.fill(gridX, gridY, gridX + gridW, gridY + gridH, PAGE_BG);

        cards.clear();
        strips.clear();
        if (shown.isEmpty() && rows.isEmpty() && hero == null) {
            String msg = loading ? "Loading servers..." : error != null ? "Couldn't reach serverlist.cc"
                    : browsing && searchable == null ? "Loading servers..."
                    : Browse.active(Telemetry.config()) ? "No servers match these filters. Change them with the funnel, top right." : "No servers match.";
            g.text(font, msg, gridX + gridW / 2 - font.width(msg) / 2, gridY + gridH / 2 - 14, GRAY);
            if (error != null && !loading) {
                String detail = truncate(error, gridW - 40);
                g.text(font, detail, gridX + gridW / 2 - font.width(detail) / 2, gridY + gridH / 2 - 3, DARK_GRAY);
            }
            return;
        }

        g.enableScissor(gridX, gridY, gridX + gridW, gridY + gridH);
        if (!shown.isEmpty()) {
            drawSearchGrid(g, mouseX, mouseY);
        } else {
            drawRows(g, mouseX, mouseY);
        }
        g.disableScissor();

        int max = maxScroll();
        if (max > 0) {
            int trackX = gridX + gridW - GRID_PAD - SCROLLBAR_W + 2;
            int thumbH = Math.max(16, innerH * innerH / (max + innerH));
            int thumbY = innerY + (innerH - thumbH) * scroll / max;
            g.blitSprite(RenderPipelines.GUI_TEXTURED, SCROLLER_BACKGROUND, trackX, innerY, SCROLLBAR_W, innerH);
            g.blitSprite(RenderPipelines.GUI_TEXTURED, SCROLLER, trackX, thumbY, SCROLLBAR_W, thumbH);
        }
    }

    /**
     * Search results, or the browsed list, as a plain grid the whole width, under a line that
     * says how many there are and how they are sorted, with "Clear filters" at its right.
     */
    private void drawSearchGrid(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        drawGridBar(g, innerY - scroll, mouseX, mouseY);
        int gridCardW = (innerW - (columns - 1) * GAP) / columns;
        String rowId = browsing ? "browse" : "search";
        for (int i = 0; i < shown.size(); i++) {
            int col = i % columns;
            int row = i / columns;
            int x = innerX + col * (gridCardW + GAP);
            int y = innerY + GRID_BAR_H + row * (CARD_H + GAP) - scroll;
            Card card = new Card(shown.get(i), x, y, gridCardW, CARD_H, false, rowId, i);
            cards.add(card);
            if (y + CARD_H < gridY - gridH || y > gridY + gridH * 2) {
                continue;
            }
            if (y + CARD_H < gridY || y > gridY + gridH) {
                // A screen away: load its art now so it is there when it scrolls in.
                Artwork.prefetch(card.server(), gridCardW, false, true);
                continue;
            }
            impression(card);
            if (inside(mouseX, mouseY, x, y, gridCardW, CARD_H)) {
                Artwork.prefetch(card.server(), innerW, true, true);
            }
            drawCard(g, card, i, mouseX, mouseY);
        }
    }

    /** The grid's top line: "312 servers Â· Newest version Â· cracked shown", and Clear filters. */
    private void drawGridBar(GuiGraphicsExtractor g, int y, int mouseX, int mouseY) {
        clearFiltersW = 0;
        TelemetryConfig c = Telemetry.config();
        boolean filtered = Browse.active(c) || seeAll;
        String count = shownTotal > shown.size()
                ? "Top " + fmt(shown.size()) + " of " + fmt(shownTotal) + " servers"
                : fmt(shownTotal) + (shownTotal == 1 ? " server" : " servers");
        if (searchable == null) {
            count += " (loading the full list...)";
        }
        String line = count + " Â· " + Browse.summary(c);
        int textY = y + (GRID_BAR_H - 8) / 2 - 2;
        if (filtered) {
            String clear = Browse.active(c) ? "Clear filters" : "Back";
            clearFiltersW = font.width(clear);
            clearFiltersX = innerX + innerW - clearFiltersW;
            clearFiltersY = textY;
            boolean hover = inside(mouseX, mouseY, clearFiltersX, clearFiltersY - 2, clearFiltersW, 12);
            g.text(font, Component.literal(clear).withStyle(hover ? ChatFormatting.UNDERLINE : ChatFormatting.RESET), clearFiltersX, textY, hover ? WHITE : AQUA, false);
        }
        int room = innerW - (filtered ? clearFiltersW + 12 : 0);
        g.text(font, truncate(line, room), innerX, textY, GRAY, false);
    }

    /**
     * The friends strip, then a titled section per row: Top servers as two rows of tiles with
     * See all, every other row a strip that scrolls sideways on its own.
     */
    private void drawRows(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int y = innerY - scroll;
        friendHits.clear();
        seeAllW = 0;
        if (!rows.isEmpty()) {
            int fh = friendsH();
            if (y + fh >= gridY && y <= gridY + gridH) {
                drawFriends(g, y, fh, mouseX, mouseY);
            }
            y += fh + ROW_GAP;
        }
        boolean inGrid = inside(mouseX, mouseY, gridX, gridY, gridW, gridH);

        for (int r = 0; r < rows.size(); r++) {
            Row row = rows.get(r);
            int stripY = y + ROW_TITLE_H;
            int bandH = row.height();
            boolean onScreen = stripY + bandH >= gridY && y <= gridY + gridH;

            if (row.grid()) {
                // The featured tile at the front, then tiles around and under it, as many across
                // as the window takes; See all for the rest.
                int span = topSpan;
                int first = span > 0 ? 1 : 0;
                int rest = span > 0 && span < columns ? columns - span : columns;
                int count = Math.min(row.servers().size(), first + rest * 2);
                int featW = span * cardW + (span - 1) * GAP;
                int featH = CARD_H * 2 + GAP;
                if (y + ROW_TITLE_H + bandH >= gridY - gridH && y <= gridY + gridH * 2) {
                    for (int i = 0; i < count; i++) {
                        boolean big = i < first;
                        Artwork.prefetch(row.servers().get(i), big ? featW : cardW, big, true);
                    }
                }
                if (onScreen) {
                    drawRowTitle(g, row, y);
                    String more = "See all " + fmt(row.source().total() + (featured != null && featured.hero() != null ? 1 : 0));
                    seeAllW = font.width(more);
                    seeAllX = innerX + innerW - seeAllW;
                    seeAllY = y + (ROW_TITLE_H - 4 - 8) / 2 + 2;
                    boolean hover = inGrid && inside(mouseX, mouseY, seeAllX, seeAllY - 2, seeAllW, 12);
                    g.text(font, more, seeAllX, seeAllY, hover ? WHITE : GRAY);
                    if (first > 0 && count > 0) {
                        Card card = new Card(row.servers().get(0), innerX, stripY, featW, featH, true, row.id(), 0);
                        cards.add(card);
                        if (stripY + featH >= gridY && stripY <= gridY + gridH) {
                            impression(card);
                            drawFeatured(g, card, mouseX, mouseY);
                        }
                    }
                    for (int i = first; i < count; i++) {
                        int j = i - first;
                        int col = span > 0 && span < columns ? span + j % rest : j % columns;
                        int line = j / rest + (span == columns ? 2 : 0);
                        int x = innerX + col * (cardW + GAP);
                        int ty = stripY + line * (CARD_H + GAP);
                        Card card = new Card(row.servers().get(i), x, ty, cardW, CARD_H, false, row.id(), i);
                        cards.add(card);
                        if (ty + CARD_H < gridY || ty > gridY + gridH) {
                            continue;
                        }
                        impression(card);
                        if (inGrid && inside(mouseX, mouseY, x, ty, cardW, CARD_H)) {
                            Artwork.prefetch(card.server(), innerW, true, true);
                        }
                        drawCard(g, card, i, mouseX, mouseY);
                    }
                }
                y += ROW_TITLE_H + bandH + ROW_GAP;
                continue;
            }

            StripScroll ss = scrollOf(row);
            int max = rowMaxScroll(row);
            ss.target = clamp(Math.round(ss.target), 0, max);
            // Within two screens of the end of what is loaded, and more to come: ask now, so
            // the strip has grown by the time the player gets there.
            if (ss.target + innerW * 2 >= max + innerW) {
                loadMore(row.source());
            }
            // Ease towards the target: a third of the way each frame, snapping when close.
            float diff = ss.target - ss.current;
            ss.current = Math.abs(diff) < 0.5f ? ss.target : ss.current + diff * 0.35f;

            int cw = row.cardW();
            int cardH = row.cardH();
            strips.add(new Strip(r, innerX, stripY, innerW, cardH, max));
            // Art for what is a screen away, down the page or along the strip, so it is loaded
            // by the time it scrolls in rather than when it does.
            if (y + ROW_TITLE_H + cardH >= gridY - gridH && y <= gridY + gridH * 2) {
                prefetchStrip(row, ss, row.compact());
            }
            if (onScreen) {
                drawRowTitle(g, row, y);
                g.enableScissor(innerX, stripY, innerX + innerW, stripY + cardH);
                int offset = Math.round(ss.current);
                for (int i = 0; i < row.servers().size(); i++) {
                    int x = innerX + i * row.pitch() - offset;
                    if (x + cw < innerX || x > innerX + innerW) {
                        continue;
                    }
                    Card card = new Card(row.servers().get(i), x, stripY, cw, cardH, false, row.id(), i);
                    cards.add(card);
                    impression(card);
                    if (!card.compact() && inGrid && inside(mouseX, mouseY, x, stripY, cw, cardH)) {
                        // Pointing at a card is the moment before opening it: fetch its page art.
                        Artwork.prefetch(card.server(), innerW, true, true);
                    }
                    if (card.compact()) {
                        drawCompactCard(g, card, i, mouseX, mouseY);
                    } else {
                        drawCard(g, card, i, mouseX, mouseY);
                    }
                }
                g.disableScissor();

                if (max > 0) {
                    Strip strip = strips.get(strips.size() - 1);
                    drawArrow(g, strip.backX(), strip.arrowY(), false, ss.target > 0,
                            inGrid && inside(mouseX, mouseY, strip.backX(), strip.arrowY(), ARROW_W, ARROW_H));
                    drawArrow(g, strip.forwardX(), strip.arrowY(), true, ss.target < max || !row.source().exhausted(),
                            inGrid && inside(mouseX, mouseY, strip.forwardX(), strip.arrowY(), ARROW_W, ARROW_H));
                }
            }
            y += ROW_TITLE_H + cardH + ROW_GAP;
        }

        // Under the last strip, where a reader who scrolled the whole page ends up: how many
        // servers there are. The play stats switch lives in Settings.
        String online = onlineCount();
        if (online != null) {
            int footY = y - ROW_GAP + 6;
            if (footY + 8 >= gridY && footY <= gridY + gridH) {
                g.text(font, online, innerX + (innerW - font.width(online)) / 2, footY, DARK_GRAY, false);
            }
        }
    }

    /** A row's title at 1.5x, in the plain font with its shadow, as the concept had it. */
    private void drawRowTitle(GuiGraphicsExtractor g, Row row, int y) {
        int titleH = Math.round(8 * ROW_TITLE_SCALE);
        textScaled(g, row.label(), innerX, y + (ROW_TITLE_H - 4 - titleH) / 2, ROW_TITLE_SCALE, WHITE);
    }

    // ------------------------------------------------------------------
    // Friends (see friends/Friends)
    // ------------------------------------------------------------------

    /** A head as laid out this frame; {@code id} null is the Add tile. */
    record FriendHit(int x, int y, int w, int h, UUID id) {
    }

    /** The names of friends on this server. */
    List<String> friendsOn(TrackedServer s) {
        List<String> out = new ArrayList<>();
        for (Friends.Friend f : Friends.snapshot().friends()) {
            if (s.slug().equals(f.on())) {
                out.add(f.name());
            }
        }
        return out;
    }

    /** A server by slug, from anything the page has loaded; null when it hasn't seen it. */
    TrackedServer serverFor(String slug) {
        if (slug == null || featured == null) {
            return null;
        }
        if (featured.hero() != null && featured.hero().slug().equals(slug)) {
            return featured.hero();
        }
        for (TrackedServer s : featured.servers()) {
            if (s.slug().equals(slug)) {
                return s;
            }
        }
        for (Featured.Row r : featured.rows()) {
            for (TrackedServer s : r.servers()) {
                if (s.slug().equals(slug)) {
                    return s;
                }
            }
        }
        if (searchable != null) {
            for (TrackedServer s : searchable) {
                if (s.slug().equals(slug)) {
                    return s;
                }
            }
        }
        return null;
    }

    /** Opens a server's page from a friend's head, the icon growing out of the head. */
    void openServer(TrackedServer s, FriendHit from) {
        selected = s;
        detail.open(new Card(s, from.x() + (from.w() - HEAD) / 2, from.y(), HEAD, HEAD, false, "friends", 0));
    }

    int columnsNow() {
        return columns;
    }

    /**
     * Servers sharing this one's main tag, busiest first, as the server page's More like this:
     * from everything the page has loaded, filtered as the page is.
     */
    List<TrackedServer> similarTo(TrackedServer s, int n) {
        List<String> tags = chipLabels(s);
        String tag = null;
        for (String t : tags) {
            if (!"Cracked".equals(t)) {
                tag = t;
                break;
            }
        }
        if (tag == null || featured == null) {
            return List.of();
        }
        Map<String, TrackedServer> pool = new LinkedHashMap<>();
        for (TrackedServer o : featured.servers()) {
            pool.putIfAbsent(o.slug(), o);
        }
        for (Featured.Row r : featured.rows()) {
            for (TrackedServer o : r.servers()) {
                pool.putIfAbsent(o.slug(), o);
            }
        }
        List<TrackedServer> out = new ArrayList<>();
        for (TrackedServer o : pool.values()) {
            if (!o.slug().equals(s.slug()) && chipLabels(o).contains(tag) && Browse.keep(o, Telemetry.config())) {
                out.add(o);
            }
        }
        out.sort(Comparator.comparingInt(TrackedServer::players).reversed());
        return out.size() > n ? out.subList(0, n) : out;
    }

    private void drawFriends(GuiGraphicsExtractor g, int y, int stripH, int mouseX, int mouseY) {
        boolean inGrid = inside(mouseX, mouseY, gridX, gridY, gridW, gridH);
        Friends.Snapshot snap = Friends.snapshot();
        List<Friends.Friend> friends = snap.friends();
        int slots = Math.max(1, (innerW + GAP) / (FRIEND_W + GAP));
        // The heads line up with the rows under them: the first square on the page's left edge,
        // and on a full row the last one on its right edge, the rest spread evenly between.
        int step = slots > 1 ? (innerW - HEAD) / (slots - 1) : 0;
        String tip = null;
        for (int i = -1; i < Math.min(friends.size(), slots - 1); i++) {
            int hx = innerX + (i + 1) * step;
            // The tile is wider than the head so the name fits; it stays inside the page.
            int x = clamp(hx - (FRIEND_W - HEAD) / 2, innerX, innerX + innerW - FRIEND_W);
            if (i < 0) {
                // With nobody added yet, a line beside the Add tile says what the row is for,
                // and clicking it opens Add too.
                String hint = friends.isEmpty() ? "Add friends to see which servers they're on." : null;
                int hintX = hx + HEAD + 10;
                int hintRoom = innerX + innerW - hintX;
                int hitW = hint != null && hintRoom > 40 ? Math.min(hintRoom, font.width(hint)) + (hintX - x) : FRIEND_W;
                boolean hover = inGrid && inside(mouseX, mouseY, x, y, hitW, stripH);
                friendHits.add(new FriendHit(x, y, hitW, stripH, null));
                // Add: an empty dashed square with a plus, and how many requests are waiting.
                boolean lit = hover || friendsPanel.mode() == FriendsPanel.Mode.ADD;
                if (lit) {
                    Round.fill(g, hx, y, HEAD, HEAD, RADIUS + 1, TILE_BG);
                }
                Round.dashed(g, hx, y, HEAD, HEAD, RADIUS + 1, lit ? WHITE : 0xFF5A5A62);
                int mid = HEAD / 2;
                g.fill(hx + mid - 1, y + mid - 7, hx + mid + 1, y + mid + 7, lit ? WHITE : GRAY);
                g.fill(hx + mid - 7, y + mid - 1, hx + mid + 7, y + mid + 1, lit ? WHITE : GRAY);
                int waiting = snap.incoming().size();
                if (waiting > 0) {
                    String n = waiting > 9 ? "9+" : String.valueOf(waiting);
                    int bw = Math.max(11, font.width(n) + 5);
                    int bx = hx + HEAD - bw + 4;
                    int by = y - 4;
                    Round.fill(g, bx - 2, by - 2, bw + 4, 15, 4, PAGE_BG);
                    Round.fill(g, bx, by, bw, 11, 3, RED);
                    g.text(font, n, bx + (bw - font.width(n) + 1) / 2, by + 2, WHITE);
                }
                centredOn(g, "Add", hx + HEAD / 2, y + HEAD + 4, WHITE, true);
                if (waiting > 0) {
                    centredOn(g, waiting + " new", hx + HEAD / 2, y + HEAD + 14, GRAY, false);
                }
                if (hint != null && hintRoom > 40) {
                    g.text(font, truncate(hint, hintRoom), hintX, y + (HEAD - 8) / 2, hover ? WHITE : GRAY, false);
                }
                if (hover && !friendsPanel.open()) {
                    tip = waiting == 1 ? "1 friend request" : waiting > 1 ? waiting + " friend requests" : "Add a friend by their Minecraft name";
                }
                continue;
            }
            Friends.Friend f = friends.get(i);
            boolean hover = inGrid && inside(mouseX, mouseY, x, y, FRIEND_W, stripH);
            friendHits.add(new FriendHit(x, y, FRIEND_W, stripH, f.id()));
            TrackedServer on = f.on() == null ? null : serverFor(f.on());
            boolean online = f.online();
            RemoteTextures.Tex head = RemoteTextures.get("head:" + f.name(), "https://mc-heads.net/avatar/" + f.name() + "/64");
            if (head != null) {
                g.blit(RenderPipelines.GUI_TEXTURED, head.id(), hx, y, 0f, 0f, HEAD, HEAD, HEAD, HEAD);
                if (!online) {
                    g.fill(hx, y, hx + HEAD, y + HEAD, 0xA0101012);
                }
                Round.mask(g, hx, y, HEAD, HEAD, RADIUS + 1, PAGE_BG);
            } else {
                Round.fill(g, hx, y, HEAD, HEAD, RADIUS + 1, TILE_BG);
            }
            if (hover || f.id().equals(friendsPanel.menuFor())) {
                Round.outline(g, hx - 1, y - 1, HEAD + 2, HEAD + 2, RADIUS + 2, WHITE);
            }
            if (on != null) {
                // Where they are: the server's icon in the corner, ringed in the page colour.
                int badge = 15;
                int bx = hx + HEAD - badge + 3;
                int by = y + HEAD - badge + 3;
                Round.fill(g, bx - 2, by - 2, badge + 4, badge + 4, 4, PAGE_BG);
                drawIcon(g, on, liveFor(on), bx, by, badge, PAGE_BG);
            } else if (online) {
                // Playing somewhere that isn't listed: just a green dot.
                int dot = 8;
                int bx = hx + HEAD - dot + 2;
                int by = y + HEAD - dot + 2;
                Round.fill(g, bx - 2, by - 2, dot + 4, dot + 4, 3, PAGE_BG);
                Round.fill(g, bx, by, dot, dot, 2, GREEN);
            }
            centredOn(g, truncate(f.name(), FRIEND_W), hx + HEAD / 2, y + HEAD + 4, online ? WHITE : GRAY, true);
            centredOn(g, truncate(on != null ? on.name() : online ? "Playing" : "Offline", FRIEND_W),
                    hx + HEAD / 2, y + HEAD + 14, online ? GRAY : DARK_GRAY, false);
            if (hover && !friendsPanel.open()) {
                tip = on != null ? f.name() + " is on " + on.name() + ". Click to see it, right-click for more."
                        : online ? f.name() + " is on a server that isn't listed. Right-click for more."
                        : f.name() + " is offline. Right-click for more.";
            }
        }
        if (tip != null) {
            g.setTooltipForNextFrame(font, Component.literal(tip), FeaturedScreen.tipX(mouseX), FeaturedScreen.tipY(mouseY));
        }
    }

    /** Text centred on {@code cx}, nudged in so it never runs past the page's edges. */
    private void centredOn(GuiGraphicsExtractor g, String text, int cx, int y, int color, boolean shadow) {
        int w = font.width(text);
        int x = clamp(cx - w / 2, innerX, innerX + innerW - w);
        g.text(font, text, x, y, color, shadow);
    }

    static void outline(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    /** Starts loading the cards within a screen either side of where the strip is and is heading. */
    private void prefetchStrip(Row row, StripScroll ss, boolean compact) {
        int pitch = row.pitch();
        float lo = Math.min(ss.current, ss.target);
        float hi = Math.max(ss.current, ss.target) + innerW;
        int from = Math.max(0, (int) ((lo - innerW) / pitch));
        int to = Math.min(row.servers().size() - 1, (int) ((hi + innerW) / pitch));
        for (int i = from; i <= to; i++) {
            Artwork.prefetch(row.servers().get(i), row.cardW(), false, !compact);
        }
    }

    private boolean overNotice(double mx, double my) {
        return noticeShowing && inside(mx, my, gridX, screenBottom() - NOTICE_H, gridW, NOTICE_H);
    }

    /** The first-open notice: what the mod shares and the three things one can do about it. */
    private void drawNotice(GuiGraphicsExtractor g) {
        if (!noticeShowing) {
            return;
        }
        int top = screenBottom() - NOTICE_H;
        g.fill(gridX, top, gridX + gridW, screenBottom(), 0xE0000000);
        g.fill(gridX, top, gridX + gridW, top + 1, 0x40FFFFFF);
        g.text(font, Component.literal("The Discovery mod shares play stats").withStyle(ChatFormatting.BOLD), innerX, top + 7, WHITE);
        String line = "Which servers you join and for how long, under your username.";
        g.text(font, truncate(line, innerW), innerX, top + 19, GRAY);
    }

    /** A strip arrow: a small dark square with a pixel chevron, greyed out at the end. */
    private void drawArrow(GuiGraphicsExtractor g, int x, int y, boolean forward, boolean enabled, boolean hovered) {
        Round.fill(g, x, y, ARROW_W, ARROW_H, 3, enabled && hovered ? TILE_HOVER : TILE_BG);
        if (enabled && hovered) {
            Round.outline(g, x, y, ARROW_W, ARROW_H, 3, WHITE);
        }
        int color = !enabled ? 0xFF55555C : hovered ? WHITE : 0xFFD0D0D0;
        int cx = x + (ARROW_W - 3) / 2;
        int cy = y + (ARROW_H - 5) / 2;
        for (int row = 0; row < 5; row++) {
            int col = row <= 2 ? row : 4 - row;
            int px = forward ? cx + col : cx + 2 - col;
            g.fill(px, cy + row, px + 1, cy + row + 1, color);
        }
    }

    /** The short card: icon at the left, name over the count, Join at the right, on a dark band. */
    private void drawCompactCard(GuiGraphicsExtractor g, Card c, int index, int mouseX, int mouseY) {
        TrackedServer s = c.server();
        ServerData ping = liveFor(s);
        boolean inGrid = inside(mouseX, mouseY, gridX, gridY, gridW, gridH);
        boolean hovered = inGrid && inside(mouseX, mouseY, c.x(), c.y(), c.w(), c.h());
        int x = c.x();
        int y = c.y();
        int w = c.w();
        int h = c.h();

        int bg = hovered ? TILE_HOVER : TILE_BG;
        Round.fill(g, x, y, w, h, RADIUS, bg);
        if (s == selected) {
            Round.outline(g, x, y, w, h, RADIUS, WHITE);
        }
        int icon = h - 8;
        drawIcon(g, s, ping, x + 4, y + 4, icon, bg);

        // Play is always there on these: they are places the player goes back to, so one click.
        int tx = x + 4 + icon + 5;
        int tw = c.joinX() - 4 - tx;
        g.text(font, truncate(s.name(), tw), tx, y + 5, WHITE);
        drawPingSprite(g, ping, index, tx, y + 17);
        String players = playersText(s, ping);
        if (font.width(players) > tw - 13) {
            players = players.replace(" online", "");
        }
        g.text(font, truncate(players, tw - 13), tx + 13, y + 17, GRAY, false);

        boolean joinHover = hovered && inside(mouseX, mouseY, c.joinX(), c.joinY(), JOIN_W, JOIN_H);
        drawPlay(g, c.joinX(), c.joinY(), JOIN_W, JOIN_H, joinHover);
    }

    /**
     * A tile, Roblox-style: the server's art at 16:9 with rounded corners, its name under it and
     * the live count under that. Pointing at it outlines the art and brings up Join and the star
     * on it; a server with no art shows its icon on a dark tile faintly tinted with its colour.
     */
    void drawCard(GuiGraphicsExtractor g, Card c, int index, int mouseX, int mouseY) {
        TrackedServer s = c.server();
        ServerData ping = liveFor(s);
        boolean inGrid = inside(mouseX, mouseY, gridX, gridY, gridW, gridH);
        boolean hovered = inGrid && inside(mouseX, mouseY, c.x(), c.y(), c.w(), c.h());
        int x = c.x();
        int y = c.y();
        int w = c.w();
        int artH = c.artH();

        RemoteTextures.Tex banner = Artwork.banner(s, w, false);
        if (banner != null) {
            blitCover(g, banner, x, y, w, artH, 0f, 1f);
        } else {
            int accent = Artwork.accent(s, ping);
            g.fill(x, y, x + w, y + artH, accent == 0 ? TILE_BG : mix(TILE_BG, accent, 0.16f));
            int tint = accent == 0 ? TILE_BG : mix(TILE_BG, accent, 0.16f);
            int icon = Math.min(32, artH - 12) / 4 * 4;
            drawIcon(g, s, ping, x + (w - icon) / 2, y + (artH - icon) / 2, icon, tint);
        }
        Round.mask(g, x, y, w, artH, RADIUS, PAGE_BG);
        if (hovered || s == selected) {
            Round.outline(g, x, y, w, artH, RADIUS, WHITE);
        }

        boolean isSaved = isStarred(s);
        if (hovered || isSaved) {
            boolean starHover = inGrid && inside(mouseX, mouseY, c.starX(), c.starY(), STAR_HIT, STAR_HIT);
            drawStar(g, c.starX() + (STAR_HIT - STAR) / 2, c.starY() + (STAR_HIT - STAR) / 2, isSaved ? 0xFFFFAA00 : starHover ? WHITE : 0xFFD0D0D0);
            if (starHover) {
                g.setTooltipForNextFrame(font, Component.literal(isSaved ? "Remove from Favourites" : "Add to Favourites"), FeaturedScreen.tipX(mouseX), FeaturedScreen.tipY(mouseY));
            }
        }
        if (hovered) {
            boolean joinHover = inside(mouseX, mouseY, c.joinX(), c.joinY(), JOIN_W, JOIN_H);
            drawPlay(g, c.joinX(), c.joinY(), JOIN_W, JOIN_H, joinHover);
        }

        // Under the art: the name, then the bars and the count. The featured server says so in
        // gold where Roblox writes "Ad"; a cracked one says so in orange.
        int ty = y + artH + 4;
        g.text(font, truncate(s.name(), w), x, ty, WHITE);
        int sy = ty + 11;
        drawPingSprite(g, ping, index, x, sy);
        int sx = x + 13;
        int room = x + w - sx;
        if (featured != null && featured.hero() == s && "top".equals(c.row())) {
            g.text(font, "Featured", sx, sy, 0xFFFFAA00, false);
            int fw = font.width("Featured ");
            sx += fw;
            room -= fw;
        } else if (s.cracked()) {
            g.text(font, "Cracked", sx, sy, CRACKED_COLOR, false);
            int fw = font.width("Cracked ");
            sx += fw;
            room -= fw;
        }
        String count = playersText(s, ping);
        if (font.width(count) > room) {
            count = playersText(s, ping).replace(" online", "");
        }
        // Browsing by version, the version is what the order is about, so it follows the count.
        if (browsing && Browse.byVersion(Telemetry.config())) {
            String v = versionLabel(s, ping);
            if (v != null) {
                count = count.replace(" online", "") + "  " + Categories.clean(v);
            }
        }
        g.text(font, truncate(count, room), sx, sy, GRAY, false);
        if (inGrid && inside(mouseX, mouseY, x, ty - 2, w, c.h() - artH - 2)) {
            g.setComponentTooltipForNextFrame(font, tooltipFor(s, ping), FeaturedScreen.tipX(mouseX), FeaturedScreen.tipY(mouseY));
        }
    }

    /**
     * The featured server, at the front of Top servers in several tiles' worth of space: all
     * art, with a Featured tag, and its icon, name, count and a line about it over a shade along
     * the bottom. Play is always showing.
     */
    private void drawFeatured(GuiGraphicsExtractor g, Card c, int mouseX, int mouseY) {
        TrackedServer s = c.server();
        ServerData ping = liveFor(s);
        boolean inGrid = inside(mouseX, mouseY, gridX, gridY, gridW, gridH);
        boolean hovered = inGrid && inside(mouseX, mouseY, c.x(), c.y(), c.w(), c.h());
        int x = c.x();
        int y = c.y();
        int w = c.w();
        int h = c.h();
        int pad = CARD_PAD;
        int radius = RADIUS + 1;

        RemoteTextures.Tex banner = Artwork.banner(s, w, true);
        if (banner != null) {
            blitCover(g, banner, x, y, w, h, 0f, 1f);
        } else {
            int accent = Artwork.accent(s, ping);
            int tint = accent == 0 ? TILE_BG : mix(TILE_BG, accent, 0.2f);
            g.fill(x, y, x + w, y + h, tint);
            int icon = Math.min(64, (h - 60) / 4 * 4);
            if (icon >= 16) {
                drawIcon(g, s, ping, x + (w - icon) / 2, y + (h - 52 - icon) / 2, icon, tint);
            }
        }
        // A shade under the text, so it reads on any art.
        g.fillGradient(x, y + h - 76, x + w, y + h, 0x00101012, 0xF0101012);
        Round.mask(g, x, y, w, h, radius, PAGE_BG);
        if (hovered || s == selected) {
            Round.outline(g, x, y, w, h, radius, WHITE);
        }

        // The tag, top left, in gold on a dark pill: what Roblox marks a sponsored game with.
        String tag = "Featured";
        int tagW = font.width(tag) + 9;
        Round.fill(g, x + pad, y + pad, tagW, 13, 3, 0xD8101012);
        g.text(font, tag, x + pad + 5, y + pad + 3, 0xFFFFAA00, false);

        boolean isSaved = isStarred(s);
        if (hovered || isSaved) {
            boolean starHover = inGrid && inside(mouseX, mouseY, c.starX(), c.starY(), STAR_HIT, STAR_HIT);
            drawStar(g, c.starX() + (STAR_HIT - STAR) / 2, c.starY() + (STAR_HIT - STAR) / 2, isSaved ? 0xFFFFAA00 : starHover ? WHITE : 0xFFD0D0D0);
            if (starHover) {
                g.setTooltipForNextFrame(font, Component.literal(isSaved ? "Remove from Favourites" : "Add to Favourites"), FeaturedScreen.tipX(mouseX), FeaturedScreen.tipY(mouseY));
            }
        }

        // Bottom row: icon on a dark plate, the name over the count, Play at the right.
        int ix = x + pad;
        int iy = y + h - pad - FEATURED_ICON;
        Round.fill(g, ix - 2, iy - 2, FEATURED_ICON + 4, FEATURED_ICON + 4, 5, HEADER_BG);
        drawIcon(g, s, ping, ix, iy, FEATURED_ICON, HEADER_BG);
        int tx = ix + FEATURED_ICON + 7;
        int room = c.joinX() - 6 - tx;
        String name = s.name();
        if (font.width(name) * 1.5f <= room) {
            textScaled(g, Component.literal(name), tx, iy + 1, 1.5f, WHITE);
        } else {
            g.text(font, truncate(name, room), tx, iy + 3, WHITE);
        }
        int sy = iy + 18;
        drawPingSprite(g, ping, 0, tx, sy);
        String count = playersText(s, ping);
        String v = versionLabel(s, ping);
        if (v != null && font.width(count + "  " + Categories.clean(v)) <= room - 13) {
            count = count + "  " + Categories.clean(v);
        } else if (font.width(count) > room - 13) {
            count = count.replace(" online", "");
        }
        g.text(font, truncate(count, room - 13), tx + 13, sy, GRAY, false);

        // One line about it, above the bottom row, when the tile is tall enough to spare it.
        String about = s.description() != null && !s.description().isBlank() ? s.description() : s.motd();
        if (about != null && h >= 110) {
            about = MANY_SPACES.matcher(about.replace('\n', ' ')).replaceAll(" ").trim();
            if (!about.isEmpty()) {
                g.text(font, truncate(about, w - pad * 2), x + pad, iy - 15, 0xFFC8C8CC, false);
            }
        }

        boolean joinHover = hovered && inside(mouseX, mouseY, c.joinX(), c.joinY(), JOIN_W, JOIN_H);
        drawPlay(g, c.joinX(), c.joinY(), JOIN_W, JOIN_H, joinHover);
        if (inGrid && inside(mouseX, mouseY, tx, iy, room, FEATURED_ICON)) {
            g.setComponentTooltipForNextFrame(font, tooltipFor(s, ping), FeaturedScreen.tipX(mouseX), FeaturedScreen.tipY(mouseY));
        }
    }

    /** Rounds a rectangle's corners by a pixel, in the page colour, the way the tiles have it. */
    private static void corners(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x, y, x + 1, y + 1, PAGE_BG);
        g.fill(x + w - 1, y, x + w, y + 1, PAGE_BG);
        g.fill(x, y + h - 1, x + 1, y + h, PAGE_BG);
        g.fill(x + w - 1, y + h - 1, x + w, y + h, PAGE_BG);
    }

    /** {@code a} moved {@code t} of the way towards {@code b}, opaque. */
    static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xFF) + ((((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t));
        int gr = Math.round(((a >> 8) & 0xFF) + ((((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t));
        int bl = Math.round((a & 0xFF) + (((b & 0xFF) - (a & 0xFF)) * t));
        return 0xFF000000 | (r << 16) | (gr << 8) | bl;
    }

    /**
     * The hero slot: the whole card is artwork, with the same pieces as a card laid over its
     * bottom edge â€” icon, name, address, status on the left; chips and Join on the right.
     */
    private void drawHero(GuiGraphicsExtractor g, Card c, int mouseX, int mouseY) {
        TrackedServer s = c.server();
        ServerData ping = liveFor(s);
        boolean inGrid = inside(mouseX, mouseY, gridX, gridY, gridW, gridH);
        boolean hovered = inGrid && inside(mouseX, mouseY, c.x(), c.y(), c.w(), c.h());
        boolean isSelected = s == selected;
        int x = c.x();
        int y = c.y();
        int w = c.w();
        int h = c.h();

        g.fill(x, y, x + w, y + h, isSelected ? WHITE : hovered ? GRAY : 0xFF000000);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF000000);
        drawBanner(g, s, ping, x + 1, y + 1, w - 2, h - 2, true);
        g.fillGradient(x + 1, y + h - 1 - 70, x + w - 1, y + h - 1, 0x00000000, 0xD8000000);

        // "Featured" tag, top-left, in the chip style.
        drawChip(g, x + CARD_PAD, y + CARD_PAD, font.width("Featured") + 10, "Featured");

        boolean starHover = inGrid && inside(mouseX, mouseY, c.starX(), c.starY(), STAR_HIT, STAR_HIT);
        boolean isSaved = isStarred(s);
        drawStar(g, c.starX() + (STAR_HIT - STAR) / 2, c.starY() + (STAR_HIT - STAR) / 2, isSaved ? 0xFFFFAA00 : starHover ? WHITE : 0xFFB0B0B0);
        if (starHover) {
            g.setTooltipForNextFrame(font, Component.literal(isSaved ? "Remove from Favourites" : "Add to Favourites"), FeaturedScreen.tipX(mouseX), FeaturedScreen.tipY(mouseY));
        }

        int ix = c.iconX();
        int iy = c.iconY();
        drawIcon(g, s, ping, ix, iy, HERO_ICON);
        if (inGrid && inside(mouseX, mouseY, ix, iy, HERO_ICON, HERO_ICON)) {
            g.setComponentTooltipForNextFrame(font, tooltipFor(s, ping), FeaturedScreen.tipX(mouseX), FeaturedScreen.tipY(mouseY));
        }

        // Name at double size â€” pixel-doubled, so it stays as crisp as the rest of the font.
        int tx = ix + HERO_ICON + 8;
        int tw = c.joinX() - 6 - tx;
        textScaled(g, truncateBold(s.name(), tw / 2), tx, iy + 1, 2f, WHITE);
        g.text(font, truncate(s.address(), tw), tx, iy + 21, GRAY);
        int sy = iy + 34;
        drawPingSprite(g, ping, 0, tx, sy);
        String players = playersText(s, ping);
        g.text(font, players, tx + 14, sy, ping.state() == ServerData.State.UNREACHABLE ? RED : GREEN);
        String latency = latencyText(ping);
        if (!latency.isEmpty()) {
            g.text(font, latency, tx + 14 + font.width(players) + 8, sy, GRAY);
        }

        // Chips, right-aligned against the Join button.
        int rowY = c.joinY();
        int chipY = rowY + (JOIN_H - CHIP_H) / 2;
        int right = c.joinX() - 6;
        int minX = tx + tw / 2;
        List<String> labels = chipLabels(s);
        List<int[]> placed = new ArrayList<>();
        int cursor = right;
        for (String label : labels) {
            int cw = font.width(label) + 10;
            if (cursor - cw < minX || placed.size() == 3) {
                break;
            }
            cursor -= cw;
            placed.add(new int[]{cursor, cw});
            cursor -= 3;
        }
        for (int i = 0; i < placed.size(); i++) {
            drawChip(g, placed.get(i)[0], chipY, placed.get(i)[1], labels.get(i));
        }

        boolean joinHover = inGrid && inside(mouseX, mouseY, c.joinX(), rowY, JOIN_W, JOIN_H);
        drawPlay(g, c.joinX(), rowY, JOIN_W, JOIN_H, joinHover);
    }

    void textScaled(GuiGraphicsExtractor g, Component text, int x, int y, float scale, int color) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        g.text(font, text, 0, 0, color);
        g.pose().popMatrix();
    }

    /**
     * The join button: green, with a white play triangle, drawn in the same bevelled style as
     * vanilla's buttons â€” dark outline, light top edge, darker bottom edge â€” and a white outline
     * when pointed at, as a vanilla button gets.
     */
    static void drawPlay(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean hover) {
        // #3B8526, a shade lighter under the mouse; the edges are the same green lightened and darkened.
        Round.fill(g, x, y, w, h, 3, hover ? WHITE : 0xFF173610);
        Round.fill(g, x + 1, y + 1, w - 2, h - 2, 2, hover ? 0xFF4A9A33 : 0xFF3B8526);
        g.fill(x + 2, y + 1, x + w - 2, y + 2, hover ? 0xFF74B85A : 0xFF5DA044);
        g.fill(x + 2, y + h - 2, x + w - 2, y + h - 1, hover ? 0xFF33701F : 0xFF2A5F1B);
        // A right-pointing triangle as wide as it is tall (9 by 9 on an 18-high button): each
        // row two pixels longer than the last, so both sides are even steps rather than a steep
        // 45 degrees. A shadow under it, like vanilla's text.
        int th = h / 2 | 1;
        int tw = th / 2 * 2 + 1;
        int tx = x + (w - tw) / 2 + 1;
        int ty = y + (h - th) / 2;
        for (int pass = 0; pass < 2; pass++) {
            int color = pass == 0 ? 0xFF1E4414 : WHITE;
            int d = pass == 0 ? 1 : 0;
            for (int i = 0; i < th; i++) {
                int len = Math.min(i, th - 1 - i) * 2 + 1;
                g.fill(tx + d, ty + i + d, tx + d + len, ty + i + d + 1, color);
            }
        }
    }

    static void drawStar(GuiGraphicsExtractor g, int x, int y, int color) {
        for (int pass = 0; pass < 2; pass++) {
            int dx = pass == 0 ? 1 : 0;
            int dy = pass == 0 ? 1 : 0;
            int c = pass == 0 ? 0xC0000000 : color;
            for (int row = 0; row < STAR_ROWS.length; row++) {
                String bits = STAR_ROWS[row];
                for (int col = 0; col < bits.length(); col++) {
                    if (bits.charAt(col) == '#') {
                        g.fill(x + col + dx, y + row + dy, x + col + dx + 1, y + row + dy + 1, c);
                    }
                }
            }
        }
    }

    /**
     * A genre chip: a pill tinted with the genre's colour, its text in a lighter shade of the
     * same â€” the label style every store front uses, drawn with square-cornered pixels so it
     * still belongs on a Minecraft screen.
     */
    void drawChip(GuiGraphicsExtractor g, int x, int y, int w, String label) {
        int color = chipColor(label);
        int fill = (shade(color, 0.32f) & 0x00FFFFFF) | 0xE8000000;
        int edge = shade(color, 0.62f);
        g.fill(x + 1, y, x + w - 1, y + CHIP_H, fill);
        g.fill(x, y + 1, x + w, y + CHIP_H - 1, fill);
        g.fill(x + 1, y, x + w - 1, y + 1, edge);
        g.fill(x + 1, y + CHIP_H - 1, x + w - 1, y + CHIP_H, edge);
        g.fill(x, y + 1, x + 1, y + CHIP_H - 1, edge);
        g.fill(x + w - 1, y + 1, x + w, y + CHIP_H - 1, edge);
        g.text(font, label, x + 5, y + 2, tint(color, 0.35f), false);
    }

    /** {@code color} moved {@code amount} of the way towards white. */
    private static int tint(int color, float amount) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        r += Math.round((255 - r) * amount);
        g += Math.round((255 - g) * amount);
        b += Math.round((255 - b) * amount);
        return (color & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    private static int chipColor(String label) {
        String l = label.toLowerCase(Locale.ROOT);
        if (l.equals("featured")) {
            return 0xFFFFAA00;
        }
        if (l.equals("cracked")) {
            return CRACKED_COLOR;
        }
        if (l.contains("minigame") || l.contains("parkour") || l.equals("uhc") || l.contains("casual")) {
            return YELLOW;
        }
        if (l.contains("skyblock") || l.contains("oneblock")) {
            return AQUA;
        }
        if (l.contains("prison") || l.contains("tycoon")) {
            return 0xFFFFAA00;
        }
        if (l.contains("pvp") || l.contains("practice") || l.contains("hcf") || l.contains("competitive") || l.contains("factions") || l.contains("lifesteal")) {
            return RED;
        }
        if (l.contains("surviv") || l.equals("smp") || l.contains("vanilla") || l.contains("towny") || l.contains("earth") || l.contains("hardcore")) {
            return GREEN;
        }
        if (l.contains("creative")) {
            return 0xFFFF55FF;
        }
        if (l.contains("anarchy")) {
            return 0xFF555555;
        }
        if (l.contains("roleplay") || l.contains("adventure") || l.contains("mmorpg") || l.contains("cobblemon") || l.contains("open world")) {
            return 0xFF5555FF;
        }
        return GRAY;
    }

    /**
     * Banner art cover-cropped into the slot; otherwise a block of the network's own colour â€”
     * its brand colour, or the one its icon is mostly made of â€” shaded so the star still reads.
     */
    /** The art, cover-cropped into the rectangle; {@code large} asks for the hero-size copy. */
    void drawBanner(GuiGraphicsExtractor g, TrackedServer s, ServerData ping, int x, int y, int w, int h, boolean large) {
        RemoteTextures.Tex banner = Artwork.banner(s, w, large);
        if (banner != null) {
            blitCover(g, banner, x, y, w, h, 0f, 1f);
            return;
        }
        int accent = Artwork.accent(s, ping);
        if (accent == 0) {
            g.fill(x, y, x + w, y + h, 0xFF1A1A1A);
            return;
        }
        g.fillGradient(x, y, x + w, y + h, shade(accent, 0.80f), shade(accent, 0.50f));
    }

    /** {@code color} with its RGB scaled by {@code factor}, alpha kept. */
    static int shade(int color, float factor) {
        int r = Math.round(((color >> 16) & 0xFF) * factor);
        int g = Math.round(((color >> 8) & 0xFF) * factor);
        int b = Math.round((color & 0xFF) * factor);
        return (color & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    /** Draws a texture into a rectangle the way CSS {@code object-fit: cover} would. */
    static void blitCover(GuiGraphicsExtractor g, RemoteTextures.Tex tex, int x, int y, int w, int h, float vMin, float vMax) {
        float texAspect = tex.width() / (float) tex.height();
        float slotAspect = w / (float) h;
        float u0 = 0f;
        float u1 = 1f;
        float v0 = vMin;
        float v1 = vMax;
        if (texAspect > slotAspect) {
            float visible = slotAspect / texAspect;
            u0 = (1f - visible) / 2f;
            u1 = u0 + visible;
        } else {
            float visible = texAspect / slotAspect;
            v0 = (1f - visible) / 2f;
            v1 = v0 + visible;
        }
        g.blit(tex.id(), x, y, x + w, y + h, u0, u1, v0, v1);
    }

    void drawIcon(GuiGraphicsExtractor g, TrackedServer s, ServerData ping, int x, int y, int size) {
        drawIcon(g, s, ping, x, y, size, 0);
    }

    /** As above, with rounded corners when {@code bg}, the colour behind the icon, is given. */
    void drawIcon(GuiGraphicsExtractor g, TrackedServer s, ServerData ping, int x, int y, int size, int bg) {
        RemoteTextures.Tex icon = Artwork.icon(s, ping);
        if (icon != null) {
            g.blit(RenderPipelines.GUI_TEXTURED, icon.id(), x, y, 0f, 0f, size, size, size, size);
            if (bg != 0) {
                Round.mask(g, x, y, size, size, Round.forSize(size), bg);
            }
        } else {
            if (bg != 0) {
                Round.fill(g, x, y, size, size, Round.forSize(size), 0xFF2B2B2B);
            } else {
                g.fill(x, y, x + size, y + size, 0xFF2B2B2B);
            }
            String letter = s.name().isEmpty() ? "?" : s.name().substring(0, 1).toUpperCase(Locale.ROOT);
            g.text(font, letter, x + size / 2 - font.width(letter) / 2, y + size / 2 - 4, GRAY);
        }
    }

    void drawPingSprite(GuiGraphicsExtractor g, ServerData ping, int index, int x, int y) {
        Identifier sprite = switch (ping.state()) {
            case INITIAL, PINGING -> {
                int frame = (int) (Util.getMillis() / 100L + index * 2 & 7L);
                if (frame > 4) {
                    frame = 8 - frame;
                }
                yield PINGING_SPRITES[clamp(frame, 0, 4)];
            }
            case UNREACHABLE -> UNREACHABLE_SPRITE;
            case INCOMPATIBLE -> INCOMPATIBLE_SPRITE;
            case SUCCESSFUL -> PING_SPRITES[ping.ping < 150 ? 4 : ping.ping < 300 ? 3 : ping.ping < 600 ? 2 : ping.ping < 1000 ? 1 : 0];
        };
        g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, 10, 8);
    }

    // ------------------------------------------------------------------
    // Text helpers
    // ------------------------------------------------------------------

    /**
     * The hover card, laid out like an item tooltip: name, address, the server's own MOTD in its
     * own colours, then the numbers as grey label / white value pairs.
     */
    private List<Component> tooltipFor(TrackedServer s, ServerData ping) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(s.name()).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD));
        lines.add(Component.literal(s.address()).withStyle(ChatFormatting.DARK_GRAY));

        Component motd = liveMotd(ping);
        if (motd == null && !s.motd().isEmpty()) {
            motd = Component.literal(s.motd());
        }
        if (motd != null) {
            List<Component> motdLines = motdLines(motd);
            if (!motdLines.isEmpty()) {
                lines.add(Component.empty());
                lines.addAll(motdLines);
            }
        }

        lines.add(Component.empty());
        boolean pinged = ping.state() == ServerData.State.SUCCESSFUL || ping.state() == ServerData.State.INCOMPATIBLE;
        int online = pinged && ping.players != null ? ping.players.online() : s.players();
        int max = pinged && ping.players != null ? ping.players.max() : s.maxPlayers();
        lines.add(stat("Players", max > 0 ? fmt(online) + " / " + fmt(max) : fmt(online)));
        if (s.peakPlayers() > 0) {
            lines.add(stat("Peak", fmt(s.peakPlayers())));
        }
        if (pinged) {
            lines.add(stat("Ping", ping.ping + " ms"));
        } else if (ping.state() == ServerData.State.UNREACHABLE) {
            lines.add(stat("Ping", "can't connect"));
        }
        String version = versionLabel(s, ping);
        if (version != null) {
            lines.add(stat("Version", version));
        }
        if (s.cracked()) {
            lines.add(Component.literal("Cracked: no Mojang account needed").withStyle(ChatFormatting.GOLD));
        }
        return lines;
    }

    private static Component stat(String label, String value) {
        return Component.literal(label + ": ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(value).withStyle(ChatFormatting.WHITE));
    }

    /**
     * The version line, only when the server's version string actually names a version. Plenty
     * of networks put something else there â€” a player count, a slogan â€” and that is noise here.
     */
    static String versionLabel(TrackedServer s, ServerData ping) {
        String v = ping.version != null && (ping.state() == ServerData.State.SUCCESSFUL || ping.state() == ServerData.State.INCOMPATIBLE)
                ? Categories.clean(ping.version.getString()) : s.version();
        if (v == null || v.isEmpty() || !VERSION_LIKE.matcher(v).find()) {
            return null;
        }
        return v.length() > 40 ? v.substring(0, 40) + "..." : v;
    }

    /**
     * A server's MOTD as tooltip lines: split on its newlines, each line's leading padding
     * dropped and runs of spaces collapsed (servers pad with dozens of them to centre text in
     * the vanilla list), with the server's own colours and styles kept.
     */
    static List<Component> motdLines(Component motd) {
        List<Component> out = new ArrayList<>();
        MutableComponent[] line = {Component.empty()};
        boolean[] hasText = {false};
        Runnable flush = () -> {
            if (hasText[0]) {
                out.add(line[0]);
            }
            line[0] = Component.empty();
            hasText[0] = false;
        };
        motd.visit((style, text) -> {
            String[] parts = text.split("\n", -1);
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) {
                    flush.run();
                }
                String part = MANY_SPACES.matcher(parts[i]).replaceAll(" ");
                if (!hasText[0]) {
                    part = part.stripLeading();
                }
                if (!part.isEmpty()) {
                    line[0].append(Component.literal(part).withStyle(style));
                    hasText[0] = true;
                }
            }
            return Optional.empty();
        }, Style.EMPTY);
        flush.run();
        return out.size() > 4 ? out.subList(0, 4) : out;
    }

    static String playersText(TrackedServer s, ServerData ping) {
        if (ping.players != null && (ping.state() == ServerData.State.SUCCESSFUL || ping.state() == ServerData.State.INCOMPATIBLE)) {
            return fmt(ping.players.online()) + " online";
        }
        if (ping.state() == ServerData.State.UNREACHABLE) {
            return "can't connect";
        }
        return fmt(s.players()) + " online";
    }

    static String latencyText(ServerData ping) {
        return switch (ping.state()) {
            case SUCCESSFUL, INCOMPATIBLE -> ping.ping + " ms";
            default -> "";
        };
    }

    static List<String> chipLabels(TrackedServer s) {
        List<String> tags = s.tags().isEmpty() ? Categories.keywordTags(s) : s.tags();
        if (!s.cracked()) {
            return tags;
        }
        List<String> out = new ArrayList<>(tags.size() + 1);
        out.add("Cracked");
        out.addAll(tags);
        return out;
    }

    static Component liveMotd(ServerData ping) {
        if (ping.state() == ServerData.State.SUCCESSFUL || ping.state() == ServerData.State.INCOMPATIBLE || ping.state() == ServerData.State.UNREACHABLE) {
            return ping.motd != null && !ping.motd.getString().isBlank() ? ping.motd : null;
        }
        return null;
    }

    Component truncateBold(String s, int maxWidth) {
        Component full = Component.literal(s).withStyle(ChatFormatting.BOLD);
        if (font.width(full) <= maxWidth) {
            return full;
        }
        String cut = s;
        while (!cut.isEmpty() && font.width(Component.literal(cut + "...").withStyle(ChatFormatting.BOLD)) > maxWidth) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return Component.literal(cut + "...").withStyle(ChatFormatting.BOLD);
    }

    String truncate(String s, int maxWidth) {
        if (s == null || maxWidth <= 0) {
            return "";
        }
        if (font.width(s) <= maxWidth) {
            return s;
        }
        int budget = Math.max(0, maxWidth - font.width("..."));
        return font.plainSubstrByWidth(s, budget) + "...";
    }

    static String fmt(int n) {
        return String.format(Locale.US, "%,d", n);
    }

    static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
