package cc.serverlist.featured.ui;

import cc.serverlist.featured.api.TrackedServer;
import cc.serverlist.featured.friends.Friends;
import cc.serverlist.featured.friends.PlayedWith;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import com.mojang.blaze3d.platform.InputConstants;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The two things that open over the friends strip: the Add box (a name field, requests either
 * way, and people played with recently) under the Add tile, and the menu for one friend under
 * their head. One at a time; a click anywhere else closes it.
 */
final class FriendsPanel {
    enum Mode { NONE, ADD, MENU }

    private static final int POP_BG = 0xFF1D1D21;
    private static final int RING = 0xFF38383F;
    private static final int WELL = 0xFF0E0E10;
    private static final int DIVIDER = 0x14FFFFFF;
    private static final int ADD_W = 232;
    private static final int MENU_W = 136;
    private static final int ROW_H = 22;
    private static final int ITEM_H = 14;

    private final FeaturedScreen screen;
    private final Font font;
    private Mode mode = Mode.NONE;
    private UUID menuFor;
    private EditBox box;
    private String message;
    private int messageColor;
    private boolean sending;

    /** Laid out this frame. */
    private int px, py, pw, ph;
    private int fieldX, fieldY, fieldW;
    private final List<Hit> hits = new ArrayList<>();

    private record Hit(int x, int y, int w, int h, Runnable run) {
    }

    FriendsPanel(FeaturedScreen screen) {
        this.screen = screen;
        this.font = Minecraft.getInstance().font;
    }

    /** The name field, made by the screen's init so it is one of its widgets; kept across resizes. */
    EditBox makeBox() {
        EditBox old = box;
        box = new EditBox(font, 0, 0, 100, 8, old, Component.literal("Minecraft username"));
        box.setBordered(false);
        box.setMaxLength(16);
        box.setHint(Component.literal("Minecraft username").withStyle(ChatFormatting.DARK_GRAY));
        box.visible = mode == Mode.ADD;
        return box;
    }

    boolean open() {
        return mode != Mode.NONE;
    }

    Mode mode() {
        return mode;
    }

    UUID menuFor() {
        return mode == Mode.MENU ? menuFor : null;
    }

    void openAdd() {
        mode = Mode.ADD;
        message = null;
        box.setValue("");
        box.visible = true;
        screen.setFocused(box);
        Friends.refresh(true);
        Friends.checkPlayedWith();
    }

    void openMenu(UUID id) {
        mode = Mode.MENU;
        menuFor = id;
        box.visible = false;
    }

    void close() {
        if (mode == Mode.ADD && screen.getFocused() == box) {
            screen.setFocused(null);
        }
        mode = Mode.NONE;
        box.visible = false;
    }

    boolean contains(double mx, double my) {
        // The nub and a pixel of ring belong to it too.
        return open() && FeaturedScreen.inside(mx, my, px - 1, py - 1, pw + 2, ph + 2);
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    /** A click inside the panel: the name field, or one of the buttons. */
    void click(double mx, double my) {
        if (mode == Mode.ADD && FeaturedScreen.inside(mx, my, fieldX, fieldY, fieldW, 18)) {
            screen.setFocused(box);
            return;
        }
        for (Hit h : List.copyOf(hits)) {
            if (FeaturedScreen.inside(mx, my, h.x(), h.y(), h.w(), h.h())) {
                screen.click();
                h.run().run();
                return;
            }
        }
    }

    /** Esc closes; Enter in the field sends. True when the key was the panel's. */
    boolean keyPressed(int key) {
        if (key == InputConstants.KEY_ESCAPE) {
            close();
            return true;
        }
        if (mode == Mode.ADD && (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER)) {
            send(box.getValue());
            return true;
        }
        return false;
    }

    private void send(String name) {
        if (sending || name.isBlank()) {
            return;
        }
        sending = true;
        Friends.request(name, r -> {
            sending = false;
            message = r.message();
            messageColor = r.ok() ? FeaturedScreen.GREEN : FeaturedScreen.RED;
            if (r.ok() && box.getValue().equalsIgnoreCase(name.trim())) {
                box.setValue("");
            }
        });
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    /** Draws whichever is open, under the tile it belongs to; closes when that tile is gone. */
    void draw(GuiGraphicsExtractor g, List<FeaturedScreen.FriendHit> tiles, int mouseX, int mouseY) {
        hits.clear();
        if (!open()) {
            return;
        }
        FeaturedScreen.FriendHit anchor = null;
        for (FeaturedScreen.FriendHit t : tiles) {
            if (mode == Mode.ADD ? t.id() == null : menuFor.equals(t.id())) {
                anchor = t;
            }
        }
        if (anchor == null || anchor.y() < screen.gridY) {
            close();
            return;
        }
        if (mode == Mode.ADD) {
            drawAdd(g, anchor, mouseX, mouseY);
        } else {
            drawMenu(g, anchor, mouseX, mouseY);
        }
    }

    /** A plain panel: one thin light rim, and a soft shadow under it. */
    private void frame(GuiGraphicsExtractor g, FeaturedScreen.FriendHit anchor) {
        Round.fill(g, px - 1, py + 1, pw + 2, ph + 3, 6, 0x50000000);
        Round.fill(g, px - 1, py - 1, pw + 2, ph + 2, 6, RING);
        Round.fill(g, px, py, pw, ph, 5, POP_BG);
    }

    // The Add box ---------------------------------------------------------

    private void drawAdd(GuiGraphicsExtractor g, FeaturedScreen.FriendHit anchor, int mouseX, int mouseY) {
        Friends.Snapshot snap = Friends.snapshot();
        pw = Math.min(ADD_W, screen.innerW);
        px = FeaturedScreen.clamp(anchor.x() - 4, screen.innerX, screen.innerX + screen.innerW - pw);
        py = anchor.y() + anchor.h() + 4;
        int room = screen.gridY + screen.gridH - 6 - py;

        if (snap.unavailable() != null) {
            box.visible = false;
            List<net.minecraft.util.FormattedCharSequence> lines = font.split(Component.literal(snap.unavailable()), pw - 16);
            ph = 8 + 12 + lines.size() * 10 + 8;
            frame(g, anchor);
            g.text(font, "Add a friend", px + 8, py + 8, FeaturedScreen.GRAY, false);
            for (int i = 0; i < lines.size(); i++) {
                g.text(font, lines.get(i), px + 8, py + 20 + i * 10, FeaturedScreen.WHITE, false);
            }
            return;
        }
        box.visible = true;

        // What goes in, trimmed to the room under the strip: the longest list gives way first.
        int requests = Math.min(4, snap.incoming().size());
        int sent = Math.min(3, snap.outgoing().size());
        List<PlayedWith.Entry> played = Friends.suggestions(3);
        int recent = played.size();
        int head = message != null ? 54 : 44;
        while (true) {
            ph = head + section(requests) + section(sent) + section(recent) + 6;
            if (ph <= room || requests + sent + recent <= 1) {
                break;
            }
            if (recent >= sent && recent >= requests && recent > 0) {
                recent--;
            } else if (sent >= requests && sent > 0) {
                sent--;
            } else {
                requests--;
            }
        }
        frame(g, anchor);

        g.text(font, "Add a friend", px + 8, py + 8, FeaturedScreen.GRAY, false);
        int sendW = 38;
        fieldX = px + 8;
        fieldY = py + 20;
        fieldW = pw - 16 - sendW - 4;
        boolean focused = screen.getFocused() == box;
        Round.fill(g, fieldX, fieldY, fieldW, 18, 3, focused ? FeaturedScreen.WHITE : 0xFF3A3A40);
        Round.fill(g, fieldX + 1, fieldY + 1, fieldW - 2, 16, 2, WELL);
        // The box is a vanilla widget, drawn and clicked in screen space rather than in the page's
        // (possibly scaled, see FeaturedScreen.contentScale) space: put it where the field shows.
        box.setX(FeaturedScreen.tipX(fieldX + 5));
        box.setY(FeaturedScreen.tipY(fieldY + 5));
        box.setWidth(Math.max(10, Math.round((fieldW - 10) * FeaturedScreen.contentScale)));
        int sx = fieldX + fieldW + 4;
        boolean canSend = !sending && !box.getValue().isBlank();
        button(g, sx, fieldY, sendW, 18, sending ? "..." : "Send", canSend ? Style.GREEN : Style.OFF, mouseX, mouseY,
                canSend ? () -> send(box.getValue()) : null);
        if (message != null) {
            g.text(font, screen.truncate(message, pw - 16), px + 8, py + 42, messageColor, false);
        }

        int y = py + head;
        if (requests > 0) {
            y = heading(g, y, "Requests", snap.incoming().size());
            for (int i = 0; i < requests; i++) {
                Friends.Person p = snap.incoming().get(i);
                int bx = px + pw - 8 - 34;
                button(g, bx, y + 4, 34, 14, "Ignore", Style.GRAY, mouseX, mouseY, () -> Friends.ignore(p));
                bx -= 4 + 38;
                button(g, bx, y + 4, 38, 14, "Accept", Style.GREEN, mouseX, mouseY, () -> {
                    Friends.accept(p);
                    message = "You and " + p.name() + " are friends.";
                    messageColor = FeaturedScreen.GREEN;
                });
                person(g, y, p.name(), "Asked " + ago(p.at()), bx - 4);
                y += ROW_H;
            }
        }
        if (sent > 0) {
            y = heading(g, y, "Sent", -1);
            for (int i = 0; i < sent; i++) {
                Friends.Person p = snap.outgoing().get(i);
                int bx = px + pw - 8 - 38;
                button(g, bx, y + 4, 38, 14, "Cancel", Style.GRAY, mouseX, mouseY, () -> Friends.cancel(p));
                long days = Math.max(0, 30 - (System.currentTimeMillis() - p.at()) / (24 * 60 * 60_000L));
                person(g, y, p.name(), System.currentTimeMillis() - p.at() < 60_000 ? "Sent just now" : "Waiting, " + days + " days left", bx - 4);
                y += ROW_H;
            }
        }
        if (recent > 0) {
            y = heading(g, y, "Played with recently", -1);
            for (int i = 0; i < recent; i++) {
                PlayedWith.Entry e = played.get(i);
                int bx = px + pw - 8 - 28;
                button(g, bx, y + 4, 28, 14, "Add", Style.GRAY, mouseX, mouseY, () -> send(e.name()));
                TrackedServer s = e.slug() == null ? null : screen.serverFor(e.slug());
                String where = s != null ? s.name() : e.address();
                person(g, y, e.name(), where + ", " + duration(e.minutes()) + " " + day(e.lastAt()), bx - 4);
                y += ROW_H;
            }
        }
    }

    private static int section(int rows) {
        return rows == 0 ? 0 : 4 + 7 + 12 + rows * ROW_H;
    }

    private int heading(GuiGraphicsExtractor g, int y, String title, int count) {
        y += 4;
        g.fill(px + 8, y, px + pw - 8, y + 1, DIVIDER);
        y += 7;
        g.text(font, title, px + 8, y, FeaturedScreen.GRAY, false);
        if (count > 0) {
            g.text(font, String.valueOf(count), px + 8 + font.width(title) + 4, y, FeaturedScreen.WHITE, false);
        }
        return y + 12;
    }

    /** A head, a name, and a grey line under it, cut short before {@code right}. */
    private void person(GuiGraphicsExtractor g, int y, String name, String sub, int right) {
        int hx = px + 8;
        head(g, name, hx, y + 3, 16);
        int tx = hx + 16 + 6;
        g.text(font, screen.truncate(name, right - tx), tx, y + 2, FeaturedScreen.WHITE);
        g.text(font, screen.truncate(sub, right - tx), tx, y + 12, FeaturedScreen.GRAY, false);
    }

    private void head(GuiGraphicsExtractor g, String name, int x, int y, int size) {
        RemoteTextures.Tex t = RemoteTextures.get("head:" + name, "https://mc-heads.net/avatar/" + name + "/64");
        if (t != null) {
            g.blit(RenderPipelines.GUI_TEXTURED, t.id(), x, y, 0f, 0f, size, size, size, size);
            Round.mask(g, x, y, size, size, 3, POP_BG);
        } else {
            Round.fill(g, x, y, size, size, 3, FeaturedScreen.TILE_BG);
        }
    }

    // The menu for one friend --------------------------------------------

    private void drawMenu(GuiGraphicsExtractor g, FeaturedScreen.FriendHit anchor, int mouseX, int mouseY) {
        Friends.Friend f = null;
        for (Friends.Friend x : Friends.snapshot().friends()) {
            if (x.id().equals(menuFor)) {
                f = x;
            }
        }
        if (f == null) {
            close();
            return;
        }
        Friends.Friend friend = f;
        TrackedServer on = f.on() == null ? null : screen.serverFor(f.on());
        pw = MENU_W;
        px = FeaturedScreen.clamp(anchor.x(), screen.innerX, screen.innerX + screen.innerW - pw);
        py = anchor.y() + anchor.h() + 4;
        ph = 26 + 5 + ITEM_H * 2 + 5 + ITEM_H * 2 + 4;
        frame(g, anchor);

        head(g, f.name(), px + 6, py + 5, 16);
        int tx = px + 28;
        g.text(font, screen.truncate(f.name(), pw - 34), tx, py + 5, FeaturedScreen.WHITE);
        String where = on != null ? "On " + on.name() : f.unlisted() ? "On an unlisted server" : f.online() ? "Online" : "Offline";
        g.text(font, screen.truncate(where, pw - 34), tx, py + 15, FeaturedScreen.GRAY, false);

        int y = py + 26;
        g.fill(px + 6, y + 2, px + pw - 6, y + 3, DIVIDER);
        y += 5;
        item(g, y, on != null ? "Join " + on.name() : "Join", on != null ? FeaturedScreen.GREEN : FeaturedScreen.DARK_GRAY, mouseX, mouseY,
                on == null ? null : () -> {
                    close();
                    screen.join(on, null);
                });
        y += ITEM_H;
        item(g, y, "Open server page", on != null ? FeaturedScreen.WHITE : FeaturedScreen.DARK_GRAY, mouseX, mouseY,
                on == null ? null : () -> {
                    close();
                    screen.openServer(on, anchor);
                });
        y += ITEM_H;
        g.fill(px + 6, y + 2, px + pw - 6, y + 3, DIVIDER);
        y += 5;
        item(g, y, "Remove friend", FeaturedScreen.WHITE, mouseX, mouseY, () -> {
            close();
            Friends.remove(friend);
        });
        y += ITEM_H;
        item(g, y, "Block", FeaturedScreen.RED, mouseX, mouseY, () -> {
            close();
            Friends.block(friend.id(), friend.name());
        });
    }

    private void item(GuiGraphicsExtractor g, int y, String label, int color, int mouseX, int mouseY, Runnable run) {
        int x = px + 3;
        int w = pw - 6;
        if (run != null && FeaturedScreen.inside(mouseX, mouseY, x, y, w, ITEM_H)) {
            Round.fill(g, x, y, w, ITEM_H, 3, FeaturedScreen.TILE_HOVER);
        }
        g.text(font, screen.truncate(label, w - 10), x + 5, y + 3, color, run != null);
        if (run != null) {
            hits.add(new Hit(x, y, w, ITEM_H, run));
        }
    }

    // Buttons -------------------------------------------------------------

    private enum Style { GREEN, GRAY, OFF }

    private void button(GuiGraphicsExtractor g, int x, int y, int w, int h, String label, Style style, int mouseX, int mouseY, Runnable run) {
        boolean hover = run != null && FeaturedScreen.inside(mouseX, mouseY, x, y, w, h);
        switch (style) {
            case GREEN -> {
                Round.fill(g, x, y, w, h, 3, hover ? FeaturedScreen.WHITE : 0xFF173610);
                Round.fill(g, x + 1, y + 1, w - 2, h - 2, 2, hover ? 0xFF4A9A33 : 0xFF3B8526);
                g.fill(x + 3, y + 1, x + w - 3, y + 2, hover ? 0xFF74B85A : 0xFF5DA044);
                g.fill(x + 3, y + h - 2, x + w - 3, y + h - 1, hover ? 0xFF33701F : 0xFF2A5F1B);
            }
            case GRAY -> {
                Round.fill(g, x, y, w, h, 3, hover ? FeaturedScreen.TILE_HOVER : FeaturedScreen.TILE_BG);
                if (hover) {
                    Round.outline(g, x, y, w, h, 3, FeaturedScreen.WHITE);
                }
            }
            case OFF -> Round.fill(g, x, y, w, h, 3, FeaturedScreen.TILE_BG);
        }
        int color = style == Style.OFF ? FeaturedScreen.DARK_GRAY : FeaturedScreen.WHITE;
        g.text(font, label, x + (w - font.width(label)) / 2, y + (h - 8) / 2, color, style != Style.OFF);
        if (run != null) {
            hits.add(new Hit(x, y, w, h, run));
        }
    }

    // Wording -------------------------------------------------------------

    /** "today", "2 days ago". */
    static String ago(long at) {
        long days = days(at);
        return days <= 0 ? "today" : days == 1 ? "yesterday" : days + " days ago";
    }

    /** "today", "yesterday", the weekday within a week, else "N days ago". */
    private static String day(long at) {
        long days = days(at);
        if (days <= 0) {
            return "today";
        }
        if (days == 1) {
            return "yesterday";
        }
        if (days < 7) {
            return Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        }
        return days + " days ago";
    }

    private static long days(long at) {
        ZoneId zone = ZoneId.systemDefault();
        return LocalDate.now(zone).toEpochDay() - Instant.ofEpochMilli(at).atZone(zone).toLocalDate().toEpochDay();
    }

    private static String duration(int minutes) {
        return minutes < 60 ? minutes + "m" : minutes / 60 + "h" + (minutes % 60 >= 10 ? " " + minutes % 60 + "m" : "");
    }
}
