package cc.serverlist.featured.ui;

import cc.serverlist.featured.compat.Compat;

import cc.serverlist.featured.friends.Friends;
import cc.serverlist.featured.telemetry.Telemetry;
import cc.serverlist.featured.telemetry.TelemetryConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Discovery's settings: tabs down the left, and on the right one row per option, its name and a
 * line saying what it does, with the control at the row's right end: a switch for on/off, a
 * {@code < value >} picker for a choice (left half steps back, right half forward; right-click
 * also steps back), or a button for an action. Opened on Sort &amp; filter from the funnel on the
 * Discovery page, on Page from the gear, and from Mod Menu's config button. Every change is
 * written to {@code config/serverlist.json} as it is made.
 *
 * <p>Everything right of the header's back arrow is drawn here rather than built from vanilla
 * widgets, in vanilla's own materials: the list background, the header separator, the button
 * nine-slice for pickers and actions.
 */
public final class SettingsScreen extends Screen {
    public enum Tab {
        FILTER("Sort & filter"),
        PAGE("Page"),
        FRIENDS("Friends"),
        BUTTONS("Buttons"),
        PRIVACY("Play stats");

        final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    private static final String PRIVACY_URL = "https://serverlist.cc/privacy";
    private static final Identifier MENU_LIST_BACKGROUND = FeaturedScreen.MENU_LIST_BACKGROUND;
    private static final Identifier HEADER_SEPARATOR = Identifier.withDefaultNamespace("textures/gui/header_separator.png");

    private static final int HEADER_H = 32;
    private static final int BAR_H = 20;
    private static final int PAD = FeaturedScreen.GRID_PAD;
    private static final int BLOCK_MAX_W = 470;
    private static final int SIDEBAR_W = 104;
    private static final int SIDEBAR_GAP = 14;
    private static final int TAB_H = 20;
    private static final int HEADING_H = 22;
    private static final int ROW_H = 32;
    private static final int CONTROL_W = 124;
    private static final int CONTROL_H = 18;
    private static final int SWITCH_W = 24;
    private static final int SWITCH_H = 12;

    private static final int WHITE = FeaturedScreen.WHITE;
    private static final int GRAY = FeaturedScreen.GRAY;
    private static final int DESC = 0xFF8C8C8C;
    private static final int DIVIDER = 0x24FFFFFF;
    private static final int SWITCH_ON = 0xFF3C8527;

    /** Where the title screen's Discovery button goes, in the order the picker offers them. */
    private enum Placement {
        AUTO("auto", "Automatic", "Beside Realms, else Mods, else Multiplayer: the first with room."),
        MULTIPLAYER("multiplayer", "By Multiplayer", "Shares the Multiplayer button's row."),
        REALMS("realms", "By Realms", "Shares the Realms button's row."),
        MODS("mods", "By Mods", "Shares Mod Menu's Mods button's row."),
        CORNER("corner", "Top-right corner", "On its own in the top-right corner, clear of the menu."),
        HIDDEN("hidden", "Hidden", "No button on the title screen. Discovery stays on the Multiplayer screen.");

        final String id;
        final String label;
        final String help;

        Placement(String id, String label, String help) {
            this.id = id;
            this.label = label;
            this.help = help;
        }

        static Placement of(String id) {
            for (Placement p : values()) {
                if (p.id.equals(id)) {
                    return p;
                }
            }
            return AUTO;
        }
    }

    // ------------------------------------------------------------------
    // Options
    // ------------------------------------------------------------------

    /** One row: a name, a line under it, an optional longer tooltip, and its control. */
    private abstract static class Option {
        final String label;
        final Supplier<String> desc;
        final Supplier<String> help;

        Option(String label, Supplier<String> desc, Supplier<String> help) {
            this.label = label;
            this.desc = desc;
            this.help = help;
        }

        int controlW() {
            return CONTROL_W;
        }

        int controlH() {
            return CONTROL_H;
        }

        abstract void draw(SettingsScreen s, GuiGraphicsExtractor g, int x, int y, double mx, double my);

        /** A click on the control, {@code mx} measured from its left edge. True when it did something. */
        abstract boolean click(double mx, int button);
    }

    private static final class Toggle extends Option {
        final BooleanSupplier get;
        final Consumer<Boolean> set;

        Toggle(String label, Supplier<String> desc, Supplier<String> help, BooleanSupplier get, Consumer<Boolean> set) {
            super(label, desc, help);
            this.get = get;
            this.set = set;
        }

        @Override
        int controlW() {
            return SWITCH_W;
        }

        @Override
        int controlH() {
            return SWITCH_H;
        }

        @Override
        void draw(SettingsScreen s, GuiGraphicsExtractor g, int x, int y, double mx, double my) {
            boolean on = get.getAsBoolean();
            boolean hover = FeaturedScreen.inside(mx, my, x, y, SWITCH_W, SWITCH_H);
            // Track: a dark rim, filled green when on, with a pixel of shade along its top.
            g.fill(x, y, x + SWITCH_W, y + SWITCH_H, hover ? WHITE : 0xFF000000);
            int track = on ? SWITCH_ON : 0xFF2A2A2A;
            g.fill(x + 1, y + 1, x + SWITCH_W - 1, y + SWITCH_H - 1, track);
            g.fill(x + 1, y + 1, x + SWITCH_W - 1, y + 2, FeaturedScreen.shade(track, 0.7f));
            // Knob: a bevelled stone-grey square, at the right when on.
            int kx = on ? x + SWITCH_W - 1 - 10 : x + 1;
            int ky = y + 1;
            g.fill(kx, ky, kx + 10, ky + 10, 0xFFC6C6C6);
            g.fill(kx, ky, kx + 10, ky + 1, 0xFFE8E8E8);
            g.fill(kx, ky + 9, kx + 10, ky + 10, 0xFF6F6F6F);
            g.fill(kx + 9, ky + 1, kx + 10, ky + 10, 0xFF6F6F6F);
        }

        @Override
        boolean click(double mx, int button) {
            set.accept(!get.getAsBoolean());
            return true;
        }
    }

    private static final class Choice<T> extends Option {
        final List<T> values;
        final Supplier<T> get;
        final Consumer<T> set;
        final java.util.function.Function<T, String> name;

        Choice(String label, Supplier<String> desc, Supplier<String> help, List<T> values,
               java.util.function.Function<T, String> name, Supplier<T> get, Consumer<T> set) {
            super(label, desc, help);
            this.values = values;
            this.name = name;
            this.get = get;
            this.set = set;
        }

        @Override
        void draw(SettingsScreen s, GuiGraphicsExtractor g, int x, int y, double mx, double my) {
            boolean hover = FeaturedScreen.inside(mx, my, x, y, CONTROL_W, CONTROL_H);
            g.blitSprite(RenderPipelines.GUI_TEXTURED, hover ? FeaturedScreen.BUTTON_HIGHLIGHTED : FeaturedScreen.BUTTON, x, y, CONTROL_W, CONTROL_H);
            boolean leftHalf = mx < x + CONTROL_W / 2.0;
            int cy = y + (CONTROL_H - 2) / 2 - 2;
            chevron(g, x + 6, cy, false, hover && leftHalf ? 0xFFFFFFA0 : 0xFFD0D0D0);
            chevron(g, x + CONTROL_W - 6 - 3, cy, true, hover && !leftHalf ? 0xFFFFFFA0 : 0xFFD0D0D0);
            String text = s.truncate(name.apply(get.get()), CONTROL_W - 30);
            g.text(s.font, text, x + (CONTROL_W - s.font.width(text)) / 2, y + (CONTROL_H - 8) / 2, hover ? 0xFFFFFFA0 : WHITE);
        }

        @Override
        boolean click(double mx, int button) {
            int i = values.indexOf(get.get());
            int step = button == 1 || mx < CONTROL_W / 2.0 ? -1 : 1;
            set.accept(values.get(Math.floorMod(i + step, values.size())));
            return true;
        }

        /** A 3x5 pixel chevron with a one-pixel shadow, pointing right or left. */
        private static void chevron(GuiGraphicsExtractor g, int x, int y, boolean right, int color) {
            for (int pass = 0; pass < 2; pass++) {
                int d = pass == 0 ? 1 : 0;
                int c = pass == 0 ? 0xFF3F3F3F : color;
                for (int row = 0; row < 5; row++) {
                    int col = row <= 2 ? row : 4 - row;
                    int px = right ? x + col : x + 2 - col;
                    g.fill(px + d, y + row + d, px + d + 1, y + row + d + 1, c);
                }
            }
        }
    }

    private static final class Action extends Option {
        final String button;
        final BooleanSupplier enabled;
        final Runnable run;

        Action(String label, Supplier<String> desc, String button, BooleanSupplier enabled, Runnable run) {
            super(label, desc, () -> null);
            this.button = button;
            this.enabled = enabled;
            this.run = run;
        }

        @Override
        void draw(SettingsScreen s, GuiGraphicsExtractor g, int x, int y, double mx, double my) {
            boolean on = enabled.getAsBoolean();
            boolean hover = on && FeaturedScreen.inside(mx, my, x, y, CONTROL_W, CONTROL_H);
            g.blitSprite(RenderPipelines.GUI_TEXTURED, !on ? FeaturedScreen.BUTTON_DISABLED : hover ? FeaturedScreen.BUTTON_HIGHLIGHTED : FeaturedScreen.BUTTON, x, y, CONTROL_W, CONTROL_H);
            g.text(s.font, button, x + (CONTROL_W - s.font.width(button)) / 2, y + (CONTROL_H - 8) / 2, !on ? 0xFFA0A0A0 : hover ? 0xFFFFFFA0 : WHITE);
        }

        @Override
        boolean click(double mx, int button) {
            if (!enabled.getAsBoolean()) {
                return false;
            }
            run.run();
            return true;
        }
    }

    // ------------------------------------------------------------------
    // Screen
    // ------------------------------------------------------------------

    private final Screen parent;
    private Tab tab;
    private List<Option> options = List.of();
    private int scroll;

    // Laid out in init.
    private int blockX, sidebarX, contentX, contentW, panelTop, panelBottom;

    public SettingsScreen(Screen parent) {
        this(parent, Tab.FILTER);
    }

    public SettingsScreen(Screen parent, Tab tab) {
        super(Component.literal("Discovery Settings"));
        this.parent = parent;
        this.tab = tab;
    }

    @Override
    protected void init() {
        int blockW = Math.min(width - PAD * 2, BLOCK_MAX_W);
        blockX = (width - blockW) / 2;
        sidebarX = blockX;
        contentX = blockX + SIDEBAR_W + SIDEBAR_GAP;
        contentW = blockX + blockW - contentX;
        panelTop = HEADER_H;
        panelBottom = height;

        addRenderableWidget(GearButton.back(PAD, (HEADER_H - 2 - BAR_H) / 2, BAR_H, b -> onClose()))
                .setTooltip(Tooltip.create(CommonComponents.GUI_BACK));
        options = optionsFor(tab);
        scroll = Math.min(scroll, maxScroll());
    }

    private List<Option> optionsFor(Tab t) {
        TelemetryConfig c = Telemetry.config();
        List<Option> out = new ArrayList<>();
        switch (t) {
            case FILTER -> {
                out.add(new Choice<>("Sort by", () -> switch (Browse.Sort.of(c.sort)) {
                    case TOP -> "Size, momentum and fit, the normal page";
                    case PLAYERS -> "Busiest first, by the raw count";
                    case FEWEST -> "Quietest first, for somewhere small";
                    case VERSION_NEW -> "By the newest version each server runs";
                    case VERSION_OLD -> "By the oldest version each accepts";
                    case RISING -> "Most players now vs this hour last week";
                    case ADDED -> "Newest on serverlist.cc first";
                    case NAME -> "Alphabetical";
                }, () -> "Anything but Top turns the page into one grid of every server, in this order.",
                        List.of(Browse.Sort.values()), s -> s.label, () -> Browse.Sort.of(c.sort), s -> {
                    c.sort = s.id;
                    save();
                }));
                out.add(new Choice<>("Version", () -> Browse.Version.of(c.version) == Browse.Version.ANY
                        ? "Every version" : "Only servers that take it",
                        () -> "Read from each server's version text. Networks that list a range count for every version from their oldest up; a server that names no version is left out.",
                        List.of(Browse.Version.values()), v -> v.label, () -> Browse.Version.of(c.version), v -> {
                    c.version = v.id;
                    save();
                }));
                out.add(new Toggle("Hide full servers", () -> "At their player limit", () -> null,
                        () -> c.hideFull, on -> {
                    c.hideFull = on;
                    save();
                }));
                out.add(new Toggle("Hide empty servers", () -> "Nobody on right now", () -> null,
                        () -> c.hideEmpty, on -> {
                    c.hideEmpty = on;
                    save();
                }));
                out.add(new Toggle("Show cracked servers", () -> "No Mojang account needed to join",
                        () -> "Shown, they get a strip of their own, turn up in search and browsing, and carry a Cracked tag. "
                                + "Anyone can log in under any name on them, so never reuse a password from elsewhere.",
                        () -> c.showCracked, on -> {
                    c.showCracked = on;
                    save();
                }));
                out.add(new Action("Reset filters", () -> "Back to the normal page, cracked stays as set", "Reset",
                        () -> Browse.active(c), () -> {
                    Browse.reset(c);
                    save();
                }));
            }
            case PAGE -> {
                out.add(new Toggle("Jump back in", () -> "Where you played lately, near the top",
                        () -> "Kept on your PC only. Hiding the row keeps the history.",
                        () -> c.showRecent, on -> {
                    c.showRecent = on;
                    save();
                }));
                out.add(new Toggle("Favourites", () -> "The servers you starred, as a strip",
                        () -> "Hiding the row keeps your stars.",
                        () -> c.showFavourites, on -> {
                    c.showFavourites = on;
                    save();
                }));
                out.add(new Choice<>("Side padding", () -> c.sidePadding == 0 ? "The page uses the full width"
                                : "Room at the sides; the page shrinks to fit",
                        () -> "Everything on the page is drawn a little smaller so each row still shows as many servers.",
                        java.util.List.of(0, 1, 2, 3, 4, 5),
                        v -> v == 0 ? "None" : v == TelemetryConfig.SIDE_PADDING_MAX ? "Max" : String.valueOf(v),
                        () -> c.sidePadding, v -> {
                    c.sidePadding = v;
                    save();
                }));
            }
            case FRIENDS -> {
                out.add(new Choice<>("Show where I'm playing", () -> "friends".equals(c.friendsShow)
                        ? "Friends see which server you're on" : "Nobody sees where you play",
                        () -> "Only the server, never the world or where you are in it. A server that isn't on serverlist.cc just shows as Playing.",
                        List.of("friends", "nobody"), v -> "friends".equals(v) ? "Friends" : "Nobody",
                        () -> "nobody".equals(c.friendsShow) ? "nobody" : "friends", v -> {
                    c.friendsShow = v;
                    saveFriends();
                }));
                out.add(new Toggle("Appear offline", () -> c.friendsOffline ? "Friends see you as offline" : "Friends see you're online",
                        () -> "You still see them.", () -> c.friendsOffline, on -> {
                    c.friendsOffline = on;
                    saveFriends();
                }));
                out.add(new Choice<>("Friend requests from", () -> switch (c.friendsFrom) {
                    case "played" -> "Only people you've played with";
                    case "nobody" -> "No one, for now";
                    default -> "Anyone who uses Discovery";
                }, () -> "Played with means you've been on a server together. Ignored requests aren't told.",
                        List.of("anyone", "played", "nobody"), v -> switch (v) {
                    case "played" -> "Played with";
                    case "nobody" -> "Nobody";
                    default -> "Anyone";
                }, () -> List.of("anyone", "played", "nobody").contains(c.friendsFrom) ? c.friendsFrom : "anyone", v -> {
                    c.friendsFrom = v;
                    saveFriends();
                }));
                out.add(new Toggle("Toast when a friend joins", () -> "Only for the server you're on",
                        () -> "One vanilla toast. Nothing in chat, and nothing about other servers.",
                        () -> c.friendsToast, on -> {
                    c.friendsToast = on;
                    save();
                }));
                for (Friends.Person p : Friends.snapshot().blocked()) {
                    out.add(new Action(p.name(), () -> "Blocked. They can't ask or see you", "Unblock", () -> true, () -> {
                        Friends.unblock(p);
                        options = optionsFor(tab);
                        scroll = Math.min(scroll, maxScroll());
                    }));
                }
            }
            case BUTTONS -> {
                List<Placement> placements = new ArrayList<>(List.of(Placement.values()));
                if (!FabricLoader.getInstance().isModLoaded("modmenu") && Placement.of(c.titleButton) != Placement.MODS) {
                    placements.remove(Placement.MODS);
                }
                out.add(new Choice<>("Title screen", () -> Placement.of(c.titleButton).help,
                        () -> "Applies the next time the title screen opens. A spot without room falls back to a corner.",
                        placements, p -> p.label, () -> Placement.of(c.titleButton), p -> {
                    c.titleButton = p.id;
                    save();
                }));
                out.add(new Toggle("Pause menu", () -> "A Discovery button under Disconnect",
                        () -> "To move to another server without going back to the title screen.",
                        () -> c.pauseButton, on -> {
                    c.pauseButton = on;
                    save();
                }));
            }
            case PRIVACY -> {
                out.add(new Toggle("Share play stats", () -> "Which servers you join and for how long",
                        () -> "So servers can be ranked by how people really play. Never chat, coordinates, search text or IP "
                                + "addresses. Turning it off also deletes what serverlist.cc holds for you.",
                        Telemetry::enabled, on -> {
                    Telemetry.setEnabled(on);
                    Telemetry.markNoticeSeen();
                }));
                out.add(new Action("Privacy policy", () -> "What is collected, and why", "Open",
                        () -> true, this::openPrivacy));
            }
        }
        return out;
    }

    private static void save() {
        Telemetry.saveConfig();
    }

    private static void saveFriends() {
        Telemetry.saveConfig();
        Friends.pushSettings();
    }

    private void openPrivacy() {
        Screen self = this;
        Compat.confirmLink(minecraft, self, PRIVACY_URL);
    }

    private int rowsTop() {
        return panelTop + 10 + HEADING_H;
    }

    private int maxScroll() {
        int content = 10 + HEADING_H + options.size() * ROW_H + 10;
        return Math.max(0, content - (panelBottom - panelTop));
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);

        // Header: the title at the Discovery page's size, over vanilla's separator.
        g.blit(RenderPipelines.GUI_TEXTURED, HEADER_SEPARATOR, 0, HEADER_H - 2, 0f, 0f, width, 2, 32, 2);
        Component title = Component.literal("Settings").withStyle(ChatFormatting.BOLD);
        g.pose().pushMatrix();
        g.pose().translate(PAD + BAR_H + 8, (HEADER_H - 2 - 12) / 2f);
        g.pose().scale(1.5f, 1.5f);
        g.text(font, title, 0, 0, WHITE);
        g.pose().popMatrix();

        // The panel: the list background under everything, the sidebar a shade darker.
        g.blit(RenderPipelines.GUI_TEXTURED, MENU_LIST_BACKGROUND, 0, panelTop, 0f, 0f, width, panelBottom - panelTop, 32, 32);
        g.fill(sidebarX, panelTop, sidebarX + SIDEBAR_W, panelBottom, 0x38000000);
        g.fill(sidebarX + SIDEBAR_W, panelTop, sidebarX + SIDEBAR_W + 1, panelBottom, 0x20FFFFFF);

        // Tabs.
        int ty = panelTop + 10;
        for (Tab t : Tab.values()) {
            boolean selected = t == tab;
            boolean hover = FeaturedScreen.inside(mouseX, mouseY, sidebarX, ty, SIDEBAR_W, TAB_H);
            if (selected) {
                g.fill(sidebarX, ty, sidebarX + SIDEBAR_W, ty + TAB_H, 0x30FFFFFF);
                g.fill(sidebarX, ty, sidebarX + 2, ty + TAB_H, WHITE);
            } else if (hover) {
                g.fill(sidebarX, ty, sidebarX + SIDEBAR_W, ty + TAB_H, 0x16FFFFFF);
            }
            String label = t.label;
            if (t == Tab.FILTER && Browse.active(Telemetry.config())) {
                // The same green dot the funnel carries while the filters are on.
                g.fill(sidebarX + SIDEBAR_W - 10, ty + 8, sidebarX + SIDEBAR_W - 7, ty + 11, FeaturedScreen.GREEN);
            }
            g.text(font, label, sidebarX + 10, ty + (TAB_H - 8) / 2, selected || hover ? WHITE : GRAY);
            ty += TAB_H;
        }

        // Rows.
        g.enableScissor(contentX, panelTop, contentX + contentW, panelBottom);
        g.text(font, Component.literal(tab.label).withStyle(ChatFormatting.BOLD), contentX, panelTop + 10 - scroll + 4, WHITE);
        int y = rowsTop() - scroll;
        Option tipFor = null;
        for (int i = 0; i < options.size(); i++) {
            Option o = options.get(i);
            int cx = contentX + contentW - o.controlW();
            int cy = y + (ROW_H - o.controlH()) / 2;
            int textW = cx - contentX - 10;
            g.text(font, truncate(o.label, textW), contentX, y + 6, WHITE);
            String d = o.desc.get();
            if (d != null) {
                g.text(font, truncate(d, textW), contentX, y + 18, DESC);
            }
            o.draw(this, g, cx, cy, mouseX, mouseY);
            if (i < options.size() - 1) {
                g.fill(contentX, y + ROW_H - 1, contentX + contentW, y + ROW_H, DIVIDER);
            }
            if (my(mouseY) && FeaturedScreen.inside(mouseX, mouseY, contentX, y, cx - contentX - 4, ROW_H) && o.help.get() != null) {
                tipFor = o;
            }
            y += ROW_H;
        }
        g.disableScissor();
        if (tipFor != null) {
            g.setComponentTooltipForNextFrame(font, wrap(tipFor.help.get(), 220), mouseX, mouseY);
        }
    }

    private boolean my(int mouseY) {
        return mouseY >= panelTop && mouseY < panelBottom;
    }

    private List<Component> wrap(String text, int maxW) {
        List<Component> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String next = line.isEmpty() ? word : line + " " + word;
            if (font.width(next) > maxW && !line.isEmpty()) {
                lines.add(Component.literal(line.toString()));
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(next);
            }
        }
        if (!line.isEmpty()) {
            lines.add(Component.literal(line.toString()));
        }
        return lines;
    }

    String truncate(String s, int maxWidth) {
        if (s == null || maxWidth <= 0) {
            return "";
        }
        if (font.width(s) <= maxWidth) {
            return s;
        }
        return font.plainSubstrByWidth(s, Math.max(0, maxWidth - font.width("..."))) + "...";
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        double mx = event.x();
        double my = event.y();
        int button = event.button();
        if (button != 0 && button != 1) {
            return false;
        }
        int ty = panelTop + 10;
        for (Tab t : Tab.values()) {
            if (button == 0 && FeaturedScreen.inside(mx, my, sidebarX, ty, SIDEBAR_W, TAB_H)) {
                if (t != tab) {
                    click();
                    tab = t;
                    scroll = 0;
                    options = optionsFor(tab);
                }
                return true;
            }
            ty += TAB_H;
        }
        if (my < panelTop) {
            return false;
        }
        int y = rowsTop() - scroll;
        for (Option o : options) {
            int cx = contentX + contentW - o.controlW();
            int cy = y + (ROW_H - o.controlH()) / 2;
            // The switch is small; its whole row's height at its column counts as a hit.
            boolean hit = o instanceof Toggle
                    ? FeaturedScreen.inside(mx, my, cx - 6, y, o.controlW() + 6, ROW_H)
                    : FeaturedScreen.inside(mx, my, cx, cy, o.controlW(), o.controlH());
            if (hit && (button == 0 || o instanceof Choice)) {
                if (o.click(mx - cx, button)) {
                    click();
                }
                return true;
            }
            y += ROW_H;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        int max = maxScroll();
        if (max > 0) {
            scroll = FeaturedScreen.clamp(scroll - (int) Math.round(sy * 16), 0, max);
            return true;
        }
        return false;
    }

    private void click() {
        AbstractWidget.playButtonClickSound(minecraft.getSoundManager());
    }

    @Override
    public void onClose() {
        Compat.setScreen(minecraft, parent);
    }
}
