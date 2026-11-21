package cc.serverlist.featured.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * The settings button on the Discovery page: vanilla's square button with a pixel-art gear on
 * it, drawn in the same white-with-shadow as a button label. The font's gear character renders
 * a few pixels tall and smudged, so the gear is drawn here instead. {@link #funnel} makes the
 * same button with a funnel on it, for Sort &amp; filter; {@link #badge} puts a small dot in its
 * corner, there to say the filters are not the defaults.
 */
public final class GearButton extends Button {
    private static final String[] GEAR = {
            "....###....",
            ".##.###.##.",
            ".#########.",
            "..#######..",
            "####...####",
            "####...####",
            "####...####",
            "..#######..",
            ".#########.",
            ".##.###.##.",
            "....###....",
    };

    private static final String[] FUNNEL = {
            "###########",
            "###########",
            ".#########.",
            "..#######..",
            "...#####...",
            "....###....",
            "....###....",
            "....###....",
            "....###....",
            "....##.....",
            "....#......",
    };


    private final String[] pixels;
    /** A dot in the top-right corner of the face. */
    public boolean badge;

    public GearButton(int x, int y, int size, OnPress onPress) {
        this(x, y, size, "Settings", GEAR, onPress);
    }

    private GearButton(int x, int y, int size, String name, String[] pixels, OnPress onPress) {
        super(x, y, size, size, Component.literal(name), onPress, DEFAULT_NARRATION);
        this.pixels = pixels;
    }

    /** Back: the font's own single angle quote, as a label. */
    public static GearButton back(int x, int y, int size, OnPress onPress) {
        return new GearButton(x, y, size, "Back", null, onPress);
    }

    public static GearButton funnel(int x, int y, int size, OnPress onPress) {
        return new GearButton(x, y, size, "Sort & filter", FUNNEL, onPress);
    }

    //? if >=1.21.11 {
    @Override
    protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
    //?} else {
    /*@Override
    protected void renderWidget(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
    *///?}
        // Flat and dark like the rest of the Discovery page, outlined white under the mouse.
        int x0 = getX(), y0 = getY(), w = getWidth(), h = getHeight();
        boolean hot = active && isHoveredOrFocused();
        Round.fill(g, x0, y0, w, h, FeaturedScreen.RADIUS, hot ? FeaturedScreen.TILE_HOVER : FeaturedScreen.TILE_BG);
        if (hot) {
            Round.outline(g, x0, y0, w, h, FeaturedScreen.RADIUS, 0xFFFFFFFF);
        }
        // Centred as it is seen, gear and shadow together: an 11-pixel gear plus its 1-pixel
        // shadow is 12 wide, so a 20-pixel button leaves 4 either side. Vertically the gear's
        // body sits in the middle of the face above the 2-pixel bevel, where a label's letters sit.
        String[] art = pixels;
        if (art == null) {
            var font = net.minecraft.client.Minecraft.getInstance().font;
            String glyph = "‹";
            g.text(font, glyph, x0 + (w - font.width(glyph)) / 2, y0 + (h - 8) / 2, active ? 0xFFFFFFFF : 0xFFA0A0A0);
            return;
        }
        int ox = getX() + (getWidth() - art[0].length() - 1) / 2;
        int oy = getY() + (getHeight() - art.length - 1) / 2;
        int a = Math.round(Math.clamp(alpha, 0f, 1f) * 255) << 24;
        for (int pass = 0; pass < 2; pass++) {
            int d = pass == 0 ? 1 : 0;
            int color = a | (pass == 0 ? 0x111113 : active ? 0xFFFFFF : 0xA0A0A0);
            for (int row = 0; row < art.length; row++) {
                String line = art[row];
                for (int col = 0; col < line.length(); col++) {
                    // Runs of pixels as one fill each.
                    if (line.charAt(col) != '#') {
                        continue;
                    }
                    int end = col;
                    while (end < line.length() && line.charAt(end) == '#') {
                        end++;
                    }
                    g.fill(ox + col + d, oy + row + d, ox + end + d, oy + row + d + 1, color);
                    col = end;
                }
            }
        }
        if (badge) {
            // A 3x3 green dot with a dark rim, clear of the art and of the button's own edge.
            int bx = getX() + getWidth() - 6;
            int by = getY() + 2;
            g.fill(bx - 1, by - 1, bx + 4, by + 4, a | 0x1F1F1F);
            g.fill(bx, by, bx + 3, by + 3, a | 0x55FF55);
        }
    }
}
