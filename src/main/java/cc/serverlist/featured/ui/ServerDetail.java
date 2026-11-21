package cc.serverlist.featured.ui;

import cc.serverlist.featured.FeaturedClient;
import cc.serverlist.featured.api.Categories;
import cc.serverlist.featured.api.ServerListApi;
import cc.serverlist.featured.api.TrackedServer;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import com.mojang.blaze3d.platform.InputConstants;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * One server, full page: what a card shows, then everything else the tracker and the live
 * ping know — the network's links, a day of player counts, the numbers. Opened by clicking a
 * card; Join is still one click, here as on the card.
 *
 * <p>Not a screen of its own. It draws over the page's rows inside {@link FeaturedScreen}, so
 * the page keeps its scroll, its pings and its telemetry session, and opening it is a fade
 * rather than a new screen.
 */
final class ServerDetail {
    private static final long OPEN_MS = 300;
    private static final long CLOSE_MS = 180;
    private static final int PAD = 12;
    private static final int BIG_ICON = 64;
    private static final int PANEL_GAP = 10;
    private static final int LINK_H = 20;
    private static final int LINK_W = 64;
    /** Buttons on the box's action row, and the row itself. */
    private static final int BTN_H = 16;
    private static final int ACTION_H = 28;
    private static final int CELL_H = 38;
    private static final int COPY_W = 40;
    private static final int CHART_H = 80;
    private static final int LABEL_COLOR = 0xFF8A8A8A;
    private static final int DIM_COLOR = 0xFFC8C8C8;
    private static final int PANEL_BG = 0xFF000000;
    private static final int PANEL_LINE = 0xFF2A2A2A;
    private static final DateTimeFormatter SINCE = DateTimeFormatter.ofPattern("d MMM yyyy");

    private final FeaturedScreen screen;
    private final Font font;

    private TrackedServer server;
    private FeaturedScreen.Card from;
    private long startedAt;
    private boolean closing;
    private int scroll;
    /** The left button while it is down: where, what was under it, and whether it became a drag. */
    private boolean pressed;
    private boolean dragging;
    private double pressX;
    private double pressY;
    private int pressScroll;
    private String pressHit;
    private int contentH;
    private long copiedAt;
    private CompletableFuture<ServerListApi.History> history;
    /** Which of {@link ServerListApi#HISTORY_RANGES} the chart shows; a day to start with. */
    private int rangeIndex;
    private static final String[] RANGE_LABELS = {"1d", "7d", "30d", "90d"};
    private final List<int[]> rangeHits = new ArrayList<>();
    /** The plot as last painted (see buildChart): what it was painted from, and each column's top row. */
    private static final Identifier CHART_ID = Identifier.fromNamespaceAndPath(FeaturedClient.MOD_ID, "chart");
    private String chartKey;
    private int[] chartTops;

    // Laid out each frame, for the clicks.
    private int joinX, joinY, joinW, joinH, favX, favY, favW, favH, copyX, copyY, copyW;
    private final List<Link> links = new ArrayList<>();
    /** More like this, as laid out this frame. */
    private final List<FeaturedScreen.Card> similar = new ArrayList<>();
    /** The page's look: the same flat dark tiles as the Discovery page. */
    private static final int R = FeaturedScreen.RADIUS;
    private static final int COL_GAP = 12;
    private static final int SECTION_GAP = 16;
    private static final int MOTD_BG = 0xFF0B0B0C;
    private static final int WELL = 0xFF0E0E10;
    private static final int CHART_LINE = 0xFFE8E8EC;

    private record Link(String label, String url, int x, int y, int w, int h) {
    }

    ServerDetail(FeaturedScreen screen) {
        this.screen = screen;
        this.font = Minecraft.getInstance().font;
    }

    boolean active() {
        return server != null;
    }

    /** Opens on the server of this card; the icon starts where the card drew it. */
    void open(FeaturedScreen.Card card) {
        server = card.server();
        from = card;
        startedAt = Util.getMillis();
        closing = false;
        scroll = 0;
        copiedAt = 0;
        setRange(rangeIndex);
        screen.detailChanged(true);
    }

    void close() {
        if (server == null || closing) {
            return;
        }
        closing = true;
        startedAt = Util.getMillis();
    }

    /** True while the rows are still part of the picture: the fade in, and the fade out. */
    boolean showsRowsBehind() {
        return progress() < 1f;
    }

    /** 0 → 1 as the page opens; 1 → 0 as it closes, then inactive. */
    private float progress() {
        long elapsed = Util.getMillis() - startedAt;
        if (closing) {
            float t = 1f - Math.min(1f, elapsed / (float) CLOSE_MS);
            if (t <= 0f) {
                server = null;
                from = null;
                closing = false;
                screen.detailChanged(false);
                return 0f;
            }
            return t;
        }
        return Math.min(1f, elapsed / (float) OPEN_MS);
    }

    private static float easeOut(float t) {
        float inv = 1f - t;
        return 1f - inv * inv * inv;
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    void draw(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        float t = easeOut(progress());
        if (server == null) {
            return;
        }
        TrackedServer s = server;
        ServerData ping = screen.liveFor(s);
        int gx = screen.gridX;
        int gy = screen.gridY;
        int gw = screen.gridW;
        int gh = screen.gridH;

        // The rows under this dim to black as it comes in.
        g.fill(gx, gy, gx + gw, gy + gh, ((int) (t * 255) << 24));

        boolean content = t >= 0.35f;
        if (content) {
            g.enableScissor(gx, gy, gx + gw, gy + gh);
            links.clear();
            drawHero(g, s, ping, mouseX, mouseY);
            g.disableScissor();
            // …and the page fades up out of that black.
            float fade = 1f - (t - 0.35f) / 0.65f;
            if (fade > 0f) {
                g.fill(gx, gy, gx + gw, gy + gh, ((int) (fade * 255) << 24));
            }
        }

        int max = Math.max(0, contentH - gh);
        if (max > 0 && content) {
            int trackX = gx + gw - FeaturedScreen.GRID_PAD - FeaturedScreen.SCROLLBAR_W + 2;
            int trackY = gy + FeaturedScreen.GRID_PAD;
            int trackH = gh - FeaturedScreen.GRID_PAD * 2;
            int thumbH = Math.max(16, trackH * trackH / (max + trackH));
            int thumbY = trackY + (trackH - thumbH) * scroll / max;
            g.fill(trackX, trackY, trackX + FeaturedScreen.SCROLLBAR_W, trackY + trackH, 0xFF000000);
            g.fill(trackX, thumbY, trackX + FeaturedScreen.SCROLLBAR_W, thumbY + thumbH, 0xFF8B8B8B);
        }
    }

    // ---- shared pieces ------------------------------------------------------------------

    /** The colour that stands for this server, lifted until it reads against black. */
    private int accent(TrackedServer s, ServerData ping) {
        int a = Artwork.accent(s, ping);
        return a == 0 ? 0xFF4A90E2 : vivid(a);
    }

    private boolean pinged(ServerData ping) {
        return ping.state() == ServerData.State.SUCCESSFUL || ping.state() == ServerData.State.INCOMPATIBLE;
    }

    private int online(TrackedServer s, ServerData ping) {
        return pinged(ping) && ping.players != null ? ping.players.online() : s.players();
    }

    private int maxPlayers(TrackedServer s, ServerData ping) {
        return pinged(ping) && ping.players != null ? ping.players.max() : s.maxPlayers();
    }

    /** The network's own links, as the API has them. */
    private List<String[]> linkDefs(TrackedServer s) {
        List<String[]> out = new ArrayList<>();
        if (s.discord() != null) {
            out.add(new String[]{"Discord", s.discord().startsWith("http") ? s.discord() : "https://" + s.discord()});
        }
        if (s.website() != null) {
            out.add(new String[]{"Website", s.website()});
        }
        if (s.store() != null) {
            out.add(new String[]{"Store", s.store()});
        }
        return out;
    }

    /** The numbers strip, as {label, value, note} cells. */
    private List<String[]> stats(TrackedServer s, ServerData ping) {
        int online = online(s, ping);
        List<String[]> tiles = new ArrayList<>();
        tiles.add(new String[]{"Online", ping.state() == ServerData.State.UNREACHABLE ? "offline" : FeaturedScreen.fmt(online), ""});
        if (s.dayAgo() >= 0) {
            tiles.add(new String[]{"Yesterday", FeaturedScreen.fmt(s.dayAgo()), delta(online, s.dayAgo())});
        }
        if (s.weekAgo() >= 0) {
            tiles.add(new String[]{"Last week", FeaturedScreen.fmt(s.weekAgo()), delta(online, s.weekAgo())});
        }
        if (s.peakPlayers() > 0) {
            tiles.add(new String[]{"Record", FeaturedScreen.fmt(s.peakPlayers()), ""});
        }
        int max = maxPlayers(s, ping);
        if (max > 0) {
            tiles.add(new String[]{"Slots", FeaturedScreen.fmt(max), ""});
        }
        return tiles;
    }

    /**
     * The page, laid out as a Roblox game page: the art on the left and what you need to join on
     * the right (both stacked on a narrow window), then the numbers, About, the player chart and
     * more servers like it.
     */
    private void drawHero(GuiGraphicsExtractor g, TrackedServer s, ServerData ping, int mouseX, int mouseY) {
        int gx = screen.gridX;
        int gy = screen.gridY;
        int gw = screen.gridW;
        int gh = screen.gridH;
        int x = screen.innerX;
        int w = screen.innerW;
        int top = gy + FeaturedScreen.GRID_PAD - scroll;
        boolean inGrid = FeaturedScreen.inside(mouseX, mouseY, gx, gy, gw, gh);
        g.fill(gx, gy, gx + gw, gy + gh, FeaturedScreen.PAGE_BG);
        similar.clear();

        boolean wide = w >= 440;
        int artW = wide ? (w - COL_GAP) * 6 / 11 : w;
        int artH = wide ? artW * 9 / 16 : Math.min(w * 9 / 16, FeaturedScreen.clamp(gh / 3, 90, 150));
        drawArt(g, s, ping, x, top, artW, artH);
        int sideX = wide ? x + artW + COL_GAP : x;
        int sideW = wide ? w - artW - COL_GAP : w;
        int sideBottom = drawSide(g, s, ping, sideX, wide ? top : top + artH + COL_GAP, sideW, mouseX, mouseY, inGrid);
        // The server's own description goes in the same column, under the buttons.
        sideBottom = drawDescription(g, s, sideX, sideBottom, sideW);

        int y = Math.max(top + artH, sideBottom) + SECTION_GAP;
        y = drawStats(g, s, ping, x, y, w) + SECTION_GAP + 4;
        y = drawAbout(g, s, ping, x, y, w, mouseX, mouseY, inGrid) + SECTION_GAP + 4;
        y = drawActivity(g, x, y, w, gh, mouseX, mouseY, inGrid) + SECTION_GAP + 4;
        y = drawSimilar(g, s, x, y, w, mouseX, mouseY);
        contentH = y + scroll - gy + FeaturedScreen.GRID_PAD;
    }

    /** The server's art, rounded; a server without any gets its icon on a faint tint of its colour. */
    private void drawArt(GuiGraphicsExtractor g, TrackedServer s, ServerData ping, int x, int y, int w, int h) {
        RemoteTextures.Tex banner = Artwork.banner(s, w, true);
        if (banner != null) {
            FeaturedScreen.blitCover(g, banner, x, y, w, h, 0f, 1f);
        } else {
            int a = Artwork.accent(s, ping);
            int tint = a == 0 ? FeaturedScreen.TILE_BG : FeaturedScreen.mix(FeaturedScreen.TILE_BG, a, 0.2f);
            g.fill(x, y, x + w, y + h, tint);
            int icon = Math.min(64, h / 2) / 4 * 4;
            screen.drawIcon(g, s, ping, x + (w - icon) / 2, y + (h - icon) / 2, icon, tint);
        }
        Round.mask(g, x, y, w, h, R + 1, FeaturedScreen.PAGE_BG);
    }

    /** Name, address, MOTD, live line, tags, Play, the action buttons and friends here. Returns the bottom. */
    private int drawSide(GuiGraphicsExtractor g, TrackedServer s, ServerData ping, int x, int y, int w, int mouseX, int mouseY, boolean inGrid) {
        int icon = 32;
        screen.drawIcon(g, s, ping, x, y, icon, FeaturedScreen.PAGE_BG);
        int tx = x + icon + 8;
        int tw = x + w - tx;
        if (font.width(s.name()) * 1.5f <= tw) {
            screen.textScaled(g, Component.literal(s.name()), tx, y + 3, 1.5f, FeaturedScreen.WHITE);
        } else {
            g.text(font, screen.truncate(s.name(), tw), tx, y + 4, FeaturedScreen.WHITE);
        }
        g.text(font, screen.truncate(s.address(), tw), tx, y + 21, FeaturedScreen.GRAY, false);
        y += icon + 8;

        // The MOTD, the server's own tagline, in its own colours on a darker well, each line centred.
        Component motd = FeaturedScreen.liveMotd(ping);
        if (motd == null && !s.motd().isEmpty()) {
            motd = Component.literal(s.motd());
        }
        if (motd != null) {
            List<Component> lines = FeaturedScreen.motdLines(motd);
            if (lines.size() > 2) {
                lines = lines.subList(0, 2);
            }
            if (!lines.isEmpty()) {
                int mh = lines.size() * 10 + 9;
                Round.fill(g, x, y, w, mh, R, MOTD_BG);
                for (int i = 0; i < lines.size(); i++) {
                    List<FormattedCharSequence> split = font.split(lines.get(i), w - 12);
                    if (split.isEmpty()) {
                        continue;
                    }
                    FormattedCharSequence seq = split.get(0);
                    g.text(font, seq, x + (w - font.width(seq)) / 2, y + 5 + i * 10, 0xFFAAAAAA);
                }
                y += mh + 7;
            }
        }

        // Live: bars, count, ping, version.
        screen.drawPingSprite(g, ping, 0, x, y);
        String players = FeaturedScreen.playersText(s, ping);
        g.text(font, players, x + 13, y, ping.state() == ServerData.State.UNREACHABLE ? FeaturedScreen.RED : FeaturedScreen.GREEN);
        int lx = x + 13 + font.width(players) + 8;
        String latency = FeaturedScreen.latencyText(ping);
        if (!latency.isEmpty()) {
            g.text(font, latency, lx, y, FeaturedScreen.GRAY, false);
            lx += font.width(latency) + 8;
        }
        String version = FeaturedScreen.versionLabel(s, ping);
        if (version != null) {
            version = Categories.clean(version);
            if (lx + font.width(version) <= x + w) {
                g.text(font, version, lx, y, FeaturedScreen.GRAY, false);
            } else {
                y += 11;
                g.text(font, screen.truncate(version, w), x, y, FeaturedScreen.GRAY, false);
            }
        }
        y += 15;

        // Tags as quiet grey pills, one row.
        int cx = x;
        for (String label : FeaturedScreen.chipLabels(s)) {
            int cw = font.width(label) + 8;
            if (cx + cw > x + w) {
                break;
            }
            Round.fill(g, cx, y, cw, 12, 2, FeaturedScreen.TILE_BG);
            g.text(font, label, cx + 4, y + 2, "Cracked".equals(label) ? 0xFFFF7744 : 0xFFD0D0D0, false);
            cx += cw + 3;
        }
        if (cx > x) {
            y += 12 + 8;
        }

        // Play: the whole column wide.
        joinX = x;
        joinY = y;
        joinW = w;
        joinH = 24;
        drawBigPlay(g, x, y, w, joinH, inGrid && FeaturedScreen.inside(mouseX, mouseY, x, y, w, joinH));
        y += joinH + 6;

        // Favourite, Copy IP, then the network's links, as many as fit three across.
        List<String[]> defs = linkDefs(s);
        int n = Math.min(3, 2 + defs.size());
        int bw = (w - (n - 1) * 4) / n;
        int bh = 18;
        links.clear();
        for (int i = 0; i < n; i++) {
            int bx = x + i * (bw + 4);
            boolean hover = inGrid && FeaturedScreen.inside(mouseX, mouseY, bx, y, bw, bh);
            Round.fill(g, bx, y, bw, bh, R, hover ? FeaturedScreen.TILE_HOVER : FeaturedScreen.TILE_BG);
            if (hover) {
                Round.outline(g, bx, y, bw, bh, R, FeaturedScreen.WHITE);
            }
            if (i == 0) {
                favX = bx;
                favY = y;
                favW = bw;
                favH = bh;
                boolean saved = screen.isStarred(s);
                String label = saved ? "Saved" : "Favourite";
                int full = FeaturedScreen.STAR + 4 + font.width(label);
                int color = saved ? 0xFFFFAA00 : FeaturedScreen.WHITE;
                if (full <= bw - 6) {
                    int sx = bx + (bw - full) / 2;
                    FeaturedScreen.drawStar(g, sx, y + (bh - FeaturedScreen.STAR) / 2, color);
                    g.text(font, label, sx + FeaturedScreen.STAR + 4, y + 5, color);
                } else {
                    FeaturedScreen.drawStar(g, bx + (bw - FeaturedScreen.STAR) / 2, y + (bh - FeaturedScreen.STAR) / 2, color);
                }
            } else if (i == 1) {
                copyX = bx;
                copyY = y;
                copyW = bw;
                boolean copied = Util.getMillis() - copiedAt < 1500;
                String label = screen.truncate(copied ? "Copied" : "Copy IP", bw - 6);
                g.text(font, label, bx + (bw - font.width(label)) / 2, y + 5, copied ? FeaturedScreen.GREEN : FeaturedScreen.WHITE);
                if (hover && !copied) {
                    g.setTooltipForNextFrame(font, Component.literal(server.address()).withStyle(ChatFormatting.GRAY), FeaturedScreen.tipX(mouseX), FeaturedScreen.tipY(mouseY));
                }
            } else {
                String[] def = defs.get(i - 2);
                String label = screen.truncate(def[0], bw - 6);
                g.text(font, label, bx + (bw - font.width(label)) / 2, y + 5, FeaturedScreen.WHITE);
                links.add(new Link(def[0], def[1], bx, y, bw, bh));
                if (hover) {
                    g.setTooltipForNextFrame(font, Component.literal(def[1]).withStyle(ChatFormatting.GRAY), FeaturedScreen.tipX(mouseX), FeaturedScreen.tipY(mouseY));
                }
            }
        }
        y += bh;

        // Friends on this server, if any.
        List<String> friends = screen.friendsOn(s);
        if (!friends.isEmpty()) {
            y += 8;
            int fx = x;
            for (int i = 0; i < Math.min(3, friends.size()); i++) {
                RemoteTextures.Tex head = RemoteTextures.get("head:" + friends.get(i), "https://mc-heads.net/avatar/" + friends.get(i) + "/64");
                if (head != null) {
                    g.blit(RenderPipelines.GUI_TEXTURED, head.id(), fx, y, 0f, 0f, 14, 14, 14, 14);
                    Round.mask(g, fx, y, 14, 14, 3, FeaturedScreen.PAGE_BG);
                }
                fx += 12;
            }
            String text = friends.size() == 1 ? friends.get(0) + " is playing"
                    : friends.get(0) + " and " + (friends.size() - 1) + (friends.size() == 2 ? " friend are" : " friends are") + " playing";
            g.text(font, screen.truncate(text, x + w - fx - 6), fx + 6, y + 3, FeaturedScreen.GRAY, false);
            y += 14;
        }
        return y;
    }

    /** The big Play: vanilla's green button, bevelled, with a play triangle and the word. */
    private void drawBigPlay(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean hover) {
        Round.fill(g, x, y, w, h, R, hover ? FeaturedScreen.WHITE : 0xFF173610);
        Round.fill(g, x + 1, y + 1, w - 2, h - 2, R - 1, hover ? 0xFF4A9A33 : 0xFF3B8526);
        g.fill(x + 3, y + 1, x + w - 3, y + 2, hover ? 0xFF74B85A : 0xFF5DA044);
        g.fill(x + 3, y + h - 3, x + w - 3, y + h - 1, hover ? 0xFF33701F : 0xFF2A5F1B);
        String label = "Play";
        int th = 9;
        int total = 5 + 5 + font.width(label);
        int tx = x + (w - total) / 2;
        int ty = y + (h - th) / 2 - 1;
        for (int pass = 0; pass < 2; pass++) {
            int color = pass == 0 ? 0xFF1E4414 : FeaturedScreen.WHITE;
            int d = pass == 0 ? 1 : 0;
            for (int i = 0; i < th; i++) {
                int len = Math.min(i, th - 1 - i) + 1;
                g.fill(tx + d, ty + i + d, tx + d + len, ty + i + d + 1, color);
            }
        }
        g.text(font, label, tx + 10, y + (h - 8) / 2, FeaturedScreen.WHITE);
    }

    /** The numbers in one rounded bar, cells split by thin lines of the page colour. */
    private int drawStats(GuiGraphicsExtractor g, TrackedServer s, ServerData ping, int x, int y, int w) {
        List<String[]> cells = stats(s, ping);
        int perRow = Math.max(1, Math.min(cells.size(), w / 78));
        int rows = (cells.size() + perRow - 1) / perRow;
        int cellH = 34;
        Round.fill(g, x, y, w, rows * cellH, R + 1, FeaturedScreen.TILE_BG);
        int cw = w / perRow;
        for (int i = 0; i < cells.size(); i++) {
            int col = i % perRow;
            int row = i / perRow;
            int cx = x + col * cw;
            int cy = y + row * cellH;
            if (col > 0) {
                g.fill(cx - 1, cy, cx + 1, cy + cellH, FeaturedScreen.PAGE_BG);
            }
            if (row > 0 && col == 0) {
                g.fill(x, cy - 1, x + w, cy + 1, FeaturedScreen.PAGE_BG);
            }
            String[] c = cells.get(i);
            g.text(font, c[0], cx + 8, cy + 6, FeaturedScreen.GRAY, false);
            screen.textScaled(g, Component.literal(c[1]), cx + 8, cy + 17, 1.5f, FeaturedScreen.WHITE);
            if (!c[2].isEmpty()) {
                int vx = cx + 8 + Math.round(font.width(c[1]) * 1.5f) + 4;
                if (vx + font.width(c[2]) <= cx + cw - 4) {
                    g.text(font, c[2], vx, cy + 21, c[2].startsWith("-") ? FeaturedScreen.RED : FeaturedScreen.GREEN, false);
                }
            }
        }
        return y + rows * cellH;
    }

    /** The description, wrapped to the column, under the buttons; nothing when the server has none. */
    private int drawDescription(GuiGraphicsExtractor g, TrackedServer s, int x, int y, int w) {
        if (s.description() == null || s.description().isBlank()) {
            return y;
        }
        y += 10;
        List<FormattedCharSequence> wrapped = font.split(Component.literal(s.description()), w);
        int n = Math.min(8, wrapped.size());
        for (int i = 0; i < n; i++) {
            g.text(font, wrapped.get(i), x, y + i * 10, DIM_COLOR);
        }
        return y + n * 10;
    }

    private void sectionTitle(GuiGraphicsExtractor g, String title, int x, int y) {
        screen.textScaled(g, Component.literal(title), x, y, 1.5f, FeaturedScreen.WHITE);
    }

    /** About: the description if there is one, then the facts as label-over-value pairs, then the links. */
    private int drawAbout(GuiGraphicsExtractor g, TrackedServer s, ServerData ping, int x, int y, int w, int mouseX, int mouseY, boolean inGrid) {
        sectionTitle(g, "About", x, y);
        y += 20;
        List<String[]> facts = new ArrayList<>();
        facts.add(new String[]{"Address", s.address()});
        String version = FeaturedScreen.versionLabel(s, ping);
        if (version != null) {
            facts.add(new String[]{"Version", Categories.clean(version)});
        }
        if (s.peakPlayers() > 0) {
            facts.add(new String[]{"Record", FeaturedScreen.fmt(s.peakPlayers()) + " players"});
        }
        if (s.addedAt() > 0) {
            facts.add(new String[]{"Listed since", SINCE.format(Instant.ofEpochMilli(s.addedAt()).atZone(ZoneId.systemDefault()))});
        }
        facts.add(new String[]{"Login", s.cracked() ? "No account needed" : "Premium only"});
        int max = maxPlayers(s, ping);
        if (max > 0) {
            facts.add(new String[]{"Slots", FeaturedScreen.fmt(max)});
        }
        int cols = w >= 520 ? 4 : w >= 300 ? 3 : 2;
        int rows = (facts.size() + cols - 1) / cols;
        int cellH = 32;
        int cw = w / cols;
        Round.fill(g, x, y, w, rows * cellH, R + 1, FeaturedScreen.TILE_BG);
        for (int r = 1; r < rows; r++) {
            g.fill(x, y + r * cellH - 1, x + w, y + r * cellH + 1, FeaturedScreen.PAGE_BG);
        }
        for (int c = 1; c < cols; c++) {
            g.fill(x + c * cw - 1, y, x + c * cw + 1, y + rows * cellH, FeaturedScreen.PAGE_BG);
        }
        for (int i = 0; i < facts.size(); i++) {
            int fx = x + (i % cols) * cw + 8;
            int fy = y + (i / cols) * cellH + 6;
            g.text(font, facts.get(i)[0], fx, fy, FeaturedScreen.GRAY, false);
            g.text(font, screen.truncate(facts.get(i)[1], cw - 14), fx, fy + 11, FeaturedScreen.WHITE);
        }
        y += rows * cellH + 6;

        // The links again, as plain text, for the ones that didn't fit up top.
        int lx = x;
        int before = links.size();
        for (String[] def : linkDefs(s)) {
            boolean shown = false;
            for (int i = 0; i < before; i++) {
                if (links.get(i).label().equals(def[0])) {
                    shown = true;
                }
            }
            if (shown) {
                continue;
            }
            int lw = font.width(def[0]);
            boolean hover = inGrid && FeaturedScreen.inside(mouseX, mouseY, lx, y + 2, lw, 10);
            g.text(font, def[0], lx, y + 4, FeaturedScreen.WHITE);
            if (hover) {
                g.fill(lx, y + 13, lx + lw, y + 14, FeaturedScreen.WHITE);
                g.setTooltipForNextFrame(font, Component.literal(def[1]).withStyle(ChatFormatting.GRAY), FeaturedScreen.tipX(mouseX), FeaturedScreen.tipY(mouseY));
            }
            links.add(new Link(def[0], def[1], lx, y + 2, lw, 12));
            lx += lw + 14;
        }
        return lx > x ? y + 16 : y;
    }

    /** Players: the chart on a rounded panel, the range picker in its corner and the summary under it. */
    private int drawActivity(GuiGraphicsExtractor g, int x, int y, int w, int gh, int mouseX, int mouseY, boolean inGrid) {
        ServerListApi.History h = historyNow();
        int pad = 10;
        int chartH = FeaturedScreen.clamp(gh / 3, CHART_H, 130);
        int panelH = pad + 18 + chartH + 6 + 10 + pad;
        Round.fill(g, x, y, w, panelH, R + 1, FeaturedScreen.TILE_BG);
        sectionTitle(g, "Players", x + pad, y + pad);
        drawRangePicker(g, x + w - pad, y + pad - 2, mouseX, mouseY, inGrid);
        drawChart(g, h, x + pad, y + pad + 18, w - pad * 2, chartH, CHART_LINE, mouseX, mouseY, inGrid);
        int sy = y + pad + 18 + chartH + 6;
        if (h != null && h.size() > 1) {
            long sum = 0;
            int n = 0;
            int hi = 0;
            int lo = Integer.MAX_VALUE;
            for (int v : h.avg()) {
                if (v >= 0) {
                    sum += v;
                    n++;
                    hi = Math.max(hi, v);
                    lo = Math.min(lo, v);
                }
            }
            if (n > 0) {
                int sx = x + pad;
                String[][] parts = {{"Average ", FeaturedScreen.fmt((int) (sum / n))}, {"Busiest ", FeaturedScreen.fmt(hi)}, {"Quietest ", FeaturedScreen.fmt(lo)}};
                for (String[] part : parts) {
                    g.text(font, part[0], sx, sy, FeaturedScreen.GRAY, false);
                    sx += font.width(part[0]);
                    g.text(font, part[1], sx, sy, FeaturedScreen.WHITE, false);
                    sx += font.width(part[1]) + 14;
                }
            }
        }
        return y + panelH;
    }

    /** More like this: one row of the Discovery page's own tiles, same main tag. */
    private int drawSimilar(GuiGraphicsExtractor g, TrackedServer s, int x, int y, int w, int mouseX, int mouseY) {
        int cols = screen.columnsNow();
        List<TrackedServer> list = screen.similarTo(s, cols);
        if (list.isEmpty()) {
            return y;
        }
        sectionTitle(g, "More like this", x, y);
        y += 20;
        int cw = (w - (cols - 1) * 6) / cols;
        int ch = cw * 9 / 16 + 25;
        for (int i = 0; i < list.size(); i++) {
            FeaturedScreen.Card card = new FeaturedScreen.Card(list.get(i), x + i * (cw + 6), y, cw, ch, false, "similar", i);
            similar.add(card);
            screen.drawCard(g, card, i, mouseX, mouseY);
        }
        return y + ch;
    }

    // ---- panels, the range picker and the chart -----------------------------------------

    private ServerListApi.History historyNow() {
        return history != null && history.isDone() && !history.isCompletedExceptionally() ? history.getNow(null) : null;
    }

    /** "+12%" against an earlier count; empty when either side is too small to mean anything. */
    private static String delta(int now, int then) {
        if (then < 25 || now < 0) {
            return "";
        }
        int pct = Math.round((now - then) * 100f / then);
        return (pct >= 0 ? "+" : "") + pct + "%";
    }

    private interface Body {
        void draw(int x, int y, int w);
    }

    /** A titled black box; returns the y below it. */
    private int panel(GuiGraphicsExtractor g, int x, int y, int w, String title, int bodyH, Body body) {
        int h = 16 + bodyH + PAD;
        g.fill(x, y, x + w, y + h, PANEL_LINE);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, PANEL_BG);
        g.text(font, title.toUpperCase(Locale.ROOT), x + PAD, y + 5, LABEL_COLOR);
        body.draw(x + PAD, y + 16, w - PAD * 2);
        return y + h + PANEL_GAP;
    }

    /** 1d · 7d · 30d · 90d, right-aligned: a dark well with the current one lit. */
    private void drawRangePicker(GuiGraphicsExtractor g, int right, int y, int mouseX, int mouseY, boolean inGrid) {
        rangeHits.clear();
        int total = 4;
        for (String label : RANGE_LABELS) {
            total += font.width(label) + 8 + 2;
        }
        Round.fill(g, right - total, y, total, 16, 3, WELL);
        int cx = right - 2;
        for (int i = RANGE_LABELS.length - 1; i >= 0; i--) {
            int bw = font.width(RANGE_LABELS[i]) + 8;
            cx -= bw;
            boolean on = i == rangeIndex;
            boolean hover = inGrid && FeaturedScreen.inside(mouseX, mouseY, cx, y + 2, bw, 12);
            if (on) {
                Round.fill(g, cx, y + 2, bw, 12, 2, FeaturedScreen.TILE_HOVER);
            }
            g.text(font, RANGE_LABELS[i], cx + 4, y + 4, on ? FeaturedScreen.WHITE : hover ? 0xFFDDDDDD : FeaturedScreen.GRAY, false);
            rangeHits.add(new int[]{i, cx, y + 2, bw, 12});
            cx -= 2;
        }
    }

    private void setRange(int i) {
        rangeIndex = i;
        history = ServerListApi.history(server.slug(), ServerListApi.HISTORY_RANGES[i]);
    }

    /**
     * The series as an area: a gradient fill under a two-pixel line in the network's colour,
     * gridlines with their values, the span on the x-axis, and under the mouse the reading at
     * that moment. Each pixel column averages the buckets it covers, so a day of minutes and
     * three months of hours draw the same.
     */
    private void drawChart(GuiGraphicsExtractor g, ServerListApi.History h, int x, int y, int w, int hgt, int lineColor, int mouseX, int mouseY, boolean inGrid) {
        int labelW = 34;
        int plotX = x + labelW;
        int plotW = w - labelW;
        int base = y + hgt - 11;
        int plotH = base - y - 4;
        if (h == null || h.size() < 2) {
            String msg = history != null && history.isCompletedExceptionally() ? "No history" : "Loading...";
            g.text(font, msg, x + (w - font.width(msg)) / 2, y + hgt / 2 - 4, FeaturedScreen.DARK_GRAY);
            return;
        }
        int[] points = h.avg();
        int peak = 1;
        for (int p : points) {
            peak = Math.max(peak, p);
        }
        int ceil = niceCeiling(peak);

        for (int i = 0; i <= 4; i++) {
            int gyLine = base - plotH * i / 4;
            g.fill(plotX, gyLine, plotX + plotW, gyLine + 1, i == 0 ? 0x30FFFFFF : 0x12FFFFFF);
            String v = shortNum(ceil * i / 4);
            g.text(font, v, plotX - 4 - font.width(v), gyLine - 4, i == 0 ? 0xFF4A4A4A : LABEL_COLOR);
        }

        int scale = (int) Math.max(1, Math.round(Minecraft.getInstance().getWindow().getGuiScale()));
        int cols = plotW * scale;
        int plotHN = plotH * scale;
        String key = server.slug() + "|" + h.range() + "|" + System.identityHashCode(h) + "|" + cols + "x" + plotHN + "|" + ceil + "|" + lineColor;
        if (!key.equals(chartKey)) {
            buildChart(h, cols, plotHN, ceil, lineColor, scale);
            chartKey = key;
        }
        int[] tops = chartTops;
        g.blit(CHART_ID, plotX, base - plotH, plotX + plotW, base, 0f, 1f, 0f, 1f);

        long first = h.at()[0];
        long last = h.at()[h.size() - 1];
        String from = first > 0 ? ago(System.currentTimeMillis() - first) : "";
        g.text(font, from, plotX, base + 3, FeaturedScreen.DARK_GRAY, false);
        String mid = first > 0 && last > first ? ago(System.currentTimeMillis() - (first + last) / 2) : "";
        g.text(font, mid, plotX + (plotW - font.width(mid)) / 2, base + 3, FeaturedScreen.DARK_GRAY, false);
        g.text(font, "now", plotX + plotW - font.width("now"), base + 3, FeaturedScreen.DARK_GRAY, false);

        if (inGrid && mouseX >= plotX && mouseX < plotX + plotW && mouseY >= y && mouseY <= base) {
            int px = Math.min(cols - 1, (mouseX - plotX) * scale + scale / 2);
            if (tops != null && tops.length == cols && tops[px] != Integer.MIN_VALUE) {
                int topY = base - plotH + tops[px] / scale;
                g.fill(mouseX, y, mouseX + 1, base, 0x60FFFFFF);
                g.fill(mouseX - 1, topY - 1, mouseX + 2, topY + 2, FeaturedScreen.WHITE);
                int i = Math.min(points.length - 1, (int) ((long) px * points.length / cols));
                int v = (int) Math.round((plotHN - 1 - tops[px]) * (double) ceil / (plotHN - 1));
                String when = h.at()[i] > 0 ? ago(System.currentTimeMillis() - h.at()[i]) : "";
                String label = FeaturedScreen.fmt(v) + (when.isEmpty() ? "" : " · " + when);
                int lw = font.width(label) + 6;
                int lx = FeaturedScreen.clamp(mouseX - lw / 2, plotX, plotX + plotW - lw);
                g.fill(lx, y - 1, lx + lw, y + 10, 0xE0000000);
                g.text(font, label, lx + 3, y, FeaturedScreen.WHITE);
            }
        }
    }

    /**
     * Paints the plot into {@link #CHART_ID}: for each pixel column the average of the buckets
     * it covers, a fill fading from the line down to the baseline, and the line itself, two
     * pixels thick and joined to its neighbours so a steep move isn't a row of dots. Column
     * tops are kept for the hover readout.
     */
    private void buildChart(ServerListApi.History h, int cols, int plotHN, int ceil, int lineColor, int scale) {
        int[] points = h.avg();
        int[] tops = new int[cols];
        NativeImage image = new NativeImage(cols, plotHN, true);
        int rgb = lineColor & 0xFFFFFF;
        for (int px = 0; px < cols; px++) {
            int i0 = (int) ((long) px * points.length / cols);
            int i1 = Math.max(i0 + 1, (int) ((long) (px + 1) * points.length / cols));
            long sum = 0;
            int n = 0;
            for (int i = i0; i < i1 && i < points.length; i++) {
                if (points[i] >= 0) {
                    sum += points[i];
                    n++;
                }
            }
            if (n == 0) {
                tops[px] = Integer.MIN_VALUE;
                continue;
            }
            tops[px] = (int) (sum / n);
        }
        // No smoothing past the per-column average: the line is what was measured.
        int radius = 0;
        int[] values = tops.clone();
        for (int px = 0; px < cols; px++) {
            if (values[px] == Integer.MIN_VALUE) {
                continue;
            }
            long sum = 0;
            int n = 0;
            for (int j = Math.max(0, px - radius); j <= Math.min(cols - 1, px + radius); j++) {
                if (values[j] != Integer.MIN_VALUE) {
                    sum += values[j];
                    n++;
                }
            }
            int v = (int) (sum / n);
            int top = plotHN - 1 - (int) ((long) (plotHN - 1) * v / ceil);
            tops[px] = top;
            // One fade for the whole plot, top to bottom, cut off by the line: shaded per
            // column, a tall column next to a short one fades at different rates and stripes.
            for (int row = top; row < plotHN; row++) {
                int alpha = 0x30 + (0x03 - 0x30) * row / Math.max(1, plotHN - 1);
                image.setPixel(px, row, (alpha << 24) | rgb);
            }
        }
        int thick = Math.max(2, scale / 2 + 1);
        int prev = Integer.MIN_VALUE;
        for (int px = 0; px < cols; px++) {
            int t = tops[px];
            if (t == Integer.MIN_VALUE) {
                prev = t;
                continue;
            }
            int a = (prev == Integer.MIN_VALUE ? t : Math.min(t, prev)) - thick / 2;
            int b = (prev == Integer.MIN_VALUE ? t : Math.max(t, prev)) + thick - thick / 2;
            for (int row = Math.max(0, a); row < Math.min(plotHN, b); row++) {
                image.setPixel(px, row, lineColor);
            }
            prev = t;
        }
        chartTops = tops;
        Minecraft.getInstance().getTextureManager().register(CHART_ID, new DynamicTexture(() -> "serverlist chart", image));
    }

    /**
     * The logo's colour, bright enough to draw a line with: a navy or maroon logo is still a
     * navy or maroon line, just lifted until it shows against black.
     */
    private static int vivid(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        int max = Math.max(r, Math.max(g, b));
        if (max >= 150) {
            return 0xFF000000 | (color & 0xFFFFFF);
        }
        float k = max == 0 ? 1f : 170f / max;
        return 0xFF000000 | (Math.min(255, Math.round(r * k)) << 16) | (Math.min(255, Math.round(g * k)) << 8) | Math.min(255, Math.round(b * k));
    }

    /** A round number a little above n, so the line fills most of the plot and the gridlines read cleanly. */
    private static int niceCeiling(int n) {
        int want = (int) Math.ceil(n * 1.08);
        int mag = 1;
        while (mag * 10 <= want) {
            mag *= 10;
        }
        for (double m : new double[]{1, 1.2, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10}) {
            if (mag * m >= want) {
                return (int) Math.round(mag * m);
            }
        }
        return mag * 10;
    }

    /** 1.2k, 45k, 300 — the gridline values, short. */
    private static String shortNum(int n) {
        if (n >= 100_000) {
            return n / 1000 + "k";
        }
        if (n >= 10_000) {
            return String.format(Locale.US, "%.0fk", n / 1000.0);
        }
        if (n >= 1000) {
            return String.format(Locale.US, "%.1fk", n / 1000.0).replace(".0k", "k");
        }
        return String.valueOf(n);
    }

    /** "3h ago", "2d ago", "now". */
    private static String ago(long ms) {
        long m = ms / 60_000;
        if (m < 2) {
            return "now";
        }
        if (m < 90) {
            return m + "m ago";
        }
        long hrs = Math.round(ms / 3_600_000.0);
        if (hrs < 36) {
            return hrs + "h ago";
        }
        return Math.round(ms / 86_400_000.0) + "d ago";
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    /**
     * The button going down only notes what is under it; the action happens when it comes back
     * up over the same thing (see {@link #mouseReleased}), and a drag in between scrolls the page
     * instead.
     */
    boolean mouseClicked(double mx, double my, int button) {
        if (server == null || closing || button != 0) {
            return true;
        }
        if (!FeaturedScreen.inside(mx, my, screen.gridX, screen.gridY, screen.gridW, screen.gridH)) {
            return false;
        }
        pressed = true;
        dragging = false;
        pressX = mx;
        pressY = my;
        pressScroll = scroll;
        pressHit = hitAt(mx, my);
        return true;
    }

    boolean mouseDragged(double mx, double my, int button) {
        if (!pressed || button != 0 || server == null || closing) {
            return false;
        }
        double ox = mx - pressX;
        double oy = my - pressY;
        if (!dragging) {
            if (ox * ox + oy * oy < 16) {
                return true;
            }
            dragging = true;
        }
        int max = Math.max(0, contentH - screen.gridH);
        scroll = FeaturedScreen.clamp((int) Math.round(pressScroll - oy), 0, max);
        return true;
    }

    boolean mouseReleased(double mx, double my, int button) {
        if (!pressed || button != 0) {
            return false;
        }
        if (!dragging) {
            // As on the page: movement may not have arrived yet; a press that moved is a drag.
            mouseDragged(mx, my, button);
        }
        pressed = false;
        if (dragging || server == null || closing) {
            return true;
        }
        String hit = hitAt(mx, my);
        if (hit != null && hit.equals(pressHit)) {
            activate(hit);
        }
        return true;
    }

    /** What a click here would do: join, star, copy, link:<i>, range:<i>, or a similar card's s<join|star|open>:<i>. */
    private String hitAt(double mx, double my) {
        if (!FeaturedScreen.inside(mx, my, screen.gridX, screen.gridY, screen.gridW, screen.gridH)) {
            return null;
        }
        if (FeaturedScreen.inside(mx, my, joinX, joinY, joinW, joinH)) {
            return "join";
        }
        if (FeaturedScreen.inside(mx, my, favX, favY, favW, favH)) {
            return "star";
        }
        if (FeaturedScreen.inside(mx, my, copyX, copyY, copyW, 18)) {
            return "copy";
        }
        for (int i = 0; i < links.size(); i++) {
            Link link = links.get(i);
            if (FeaturedScreen.inside(mx, my, link.x(), link.y(), link.w(), link.h())) {
                return "link:" + i;
            }
        }
        for (int[] hit : rangeHits) {
            if (FeaturedScreen.inside(mx, my, hit[1], hit[2], hit[3], hit[4]) && hit[0] != rangeIndex) {
                return "range:" + hit[0];
            }
        }
        for (int i = 0; i < similar.size(); i++) {
            FeaturedScreen.Card c = similar.get(i);
            if (FeaturedScreen.inside(mx, my, c.x(), c.y(), c.w(), c.h())) {
                if (FeaturedScreen.inside(mx, my, c.joinX(), c.joinY(), FeaturedScreen.JOIN_W, FeaturedScreen.JOIN_H)) {
                    return "sjoin:" + i;
                }
                if (FeaturedScreen.inside(mx, my, c.starX(), c.starY(), FeaturedScreen.STAR_HIT, FeaturedScreen.STAR_HIT)) {
                    return "sstar:" + i;
                }
                return "sopen:" + i;
            }
        }
        return null;
    }

    private void activate(String hit) {
        screen.click();
        if (hit.equals("join")) {
            screen.join(server, from);
        } else if (hit.equals("star")) {
            screen.toggleSaved(server);
        } else if (hit.equals("copy")) {
            Minecraft.getInstance().keyboardHandler.setClipboard(server.address());
            copiedAt = Util.getMillis();
        } else if (hit.startsWith("link:")) {
            int i = Integer.parseInt(hit.substring(5));
            if (i < links.size()) {
                screen.openLink(links.get(i).url());
            }
        } else if (hit.startsWith("range:")) {
            setRange(Integer.parseInt(hit.substring(6)));
        } else if (hit.startsWith("s")) {
            int i = Integer.parseInt(hit.substring(hit.indexOf(':') + 1));
            if (i >= similar.size()) {
                return;
            }
            FeaturedScreen.Card c = similar.get(i);
            if (hit.startsWith("sjoin:")) {
                screen.join(c.server(), c);
            } else if (hit.startsWith("sstar:")) {
                screen.toggleSaved(c.server());
            } else {
                // Another server's page, from the top, without the fade.
                server = c.server();
                from = c;
                scroll = 0;
                copiedAt = 0;
                chartKey = null;
                setRange(rangeIndex);
            }
        }
    }

    boolean mouseScrolled(double mx, double my, double sy) {
        int max = Math.max(0, contentH - screen.gridH);
        scroll = FeaturedScreen.clamp((int) Math.round(scroll - sy * 30), 0, max);
        return true;
    }

    boolean keyPressed(int key) {
        if (key == InputConstants.KEY_ESCAPE || key == InputConstants.KEY_BACKSPACE) {
            close();
            return true;
        }
        if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
            screen.join(server, from);
            return true;
        }
        return false;
    }
}
