package cc.serverlist.featured.ui;

import cc.serverlist.featured.FeaturedClient;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Rounded corners for the Discovery page's tiles, icons, heads and buttons, drawn at the
 * screen's own resolution rather than in GUI pixels, and cheaply: each corner is one quad.
 *
 * <p>Everything else on the page is laid out in GUI pixels, which are 2, 3 or more real pixels
 * across, so a corner stepped in GUI pixels looks like stairs. Here the straight parts are
 * ordinary GUI fills, and the four corners are quarters of a small circle texture worked out
 * once at the screen's real resolution: each texel's alpha is the share of it the circle
 * covers, which is what anti-aliasing is. A radius of 4 at GUI scale 3 is a 12-pixel curve.
 * The first version drew those pixels as fills every frame, a few thousand per frame across
 * the page, and the frame rate showed it.
 *
 * <p>{@link #fill} draws the rounded shape. Images can't be clipped to a shape, so {@link #mask}
 * paints the colour behind the image over the part of each corner outside the curve instead,
 * which only works on a solid background; everything on the page sits on one. {@link #outline}
 * draws a one-GUI-pixel ring along the same curve.
 */
final class Round {
    private enum Mode { FILL, MASK, OUTLINE }

    private Round() {
    }

    /** The radius that suits a square this size: an eighth of it, at least 2. */
    static int forSize(int size) {
        return Math.max(2, size / 8);
    }

    private static int scale() {
        return Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
    }

    static void fill(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int color) {
        r = Math.min(r, Math.min(w, h) / 2);
        if (r <= 0) {
            g.fill(x, y, x + w, y + h, color);
            return;
        }
        g.fill(x, y + r, x + w, y + h - r, color);
        g.fill(x + r, y, x + w - r, y + r, color);
        g.fill(x + r, y + h - r, x + w - r, y + h, color);
        corners(g, x, y, w, h, r, color, Mode.FILL);
    }

    /** Paints {@code bg} over the corners of something already drawn at this rectangle. */
    static void mask(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int bg) {
        r = Math.min(r, Math.min(w, h) / 2);
        if (r > 0) {
            corners(g, x, y, w, h, r, bg, Mode.MASK);
        }
    }

    /** A one-GUI-pixel line along the rounded edge. */
    static void outline(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int color) {
        r = Math.min(r, Math.min(w, h) / 2);
        g.fill(x + r, y, x + w - r, y + 1, color);
        g.fill(x + r, y + h - 1, x + w - r, y + h, color);
        g.fill(x, y + r, x + 1, y + h - r, color);
        g.fill(x + w - 1, y + r, x + w, y + h - r, color);
        if (r > 0) {
            corners(g, x, y, w, h, r, color, Mode.OUTLINE);
        }
    }

    /**
     * The outline, dashed along the straight sides: the "nothing here yet" box. The corners
     * stay solid curves, and each side's dashes are centred so the gaps at both ends match.
     */
    static void dashed(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int color) {
        r = Math.min(r, Math.min(w, h) / 2);
        int dash = 3;
        int gap = 2;
        dashes(g, x + r, x + w - r, y, true, dash, gap, color);
        dashes(g, x + r, x + w - r, y + h - 1, true, dash, gap, color);
        dashes(g, y + r, y + h - r, x, false, dash, gap, color);
        dashes(g, y + r, y + h - r, x + w - 1, false, dash, gap, color);
        if (r > 0) {
            corners(g, x, y, w, h, r, color, Mode.OUTLINE);
        }
    }

    /** Dashes from {@code from} to {@code to} along one side, a gap at each end, centred. */
    private static void dashes(GuiGraphicsExtractor g, int from, int to, int at, boolean across, int dash, int gap, int color) {
        int len = to - from - gap * 2;
        int n = (len + gap) / (dash + gap);
        if (n <= 0) {
            return;
        }
        int start = from + gap + (len - (n * (dash + gap) - gap)) / 2;
        for (int i = 0; i < n; i++) {
            int a = start + i * (dash + gap);
            if (across) {
                g.fill(a, at, a + dash, at + 1, color);
            } else {
                g.fill(at, a, at + 1, a + dash, color);
            }
        }
    }

    private static void corners(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int color, Mode mode) {
        corner(g, x, y, r, color, mode, false, false);
        corner(g, x + w - r, y, r, color, mode, true, false);
        corner(g, x, y + h - r, r, color, mode, false, true);
        corner(g, x + w - r, y + h - r, r, color, mode, true, true);
    }

    /**
     * One corner: the {@code r} by {@code r} GUI square at ({@code gx}, {@code gy}), as one
     * quarter of a cached circle texture, tinted with {@code color}. The texture is white with
     * the coverage worked out once per radius, scale and mode as its alpha, and is exactly
     * {@code 2r} GUI pixels across in real pixels, so it is drawn at one texel per screen pixel.
     */
    private static void corner(GuiGraphicsExtractor g, int gx, int gy, int r, int color, Mode mode, boolean flipX, boolean flipY) {
        Identifier id = texture(r, scale(), mode);
        if (id == null) {
            return;
        }
        g.blit(RenderPipelines.GUI_TEXTURED, id, gx, gy, flipX ? r : 0, flipY ? r : 0, r, r, r * 2, r * 2, color);
    }

    private static final Map<String, Identifier> TEXTURES = new HashMap<>();

    /**
     * The circle for this radius at this GUI scale: {@code 2R} real pixels square, where R is the
     * radius in real pixels. FILL holds the disc, MASK everything outside it, OUTLINE a ring one
     * GUI pixel wide. Built on first use and kept; there are only ever a handful.
     */
    private static Identifier texture(int r, int s, Mode mode) {
        String key = mode.name().toLowerCase(Locale.ROOT) + "_" + r + "_" + s;
        Identifier cached = TEXTURES.get(key);
        if (cached != null || TEXTURES.containsKey(key)) {
            return cached;
        }
        int size = r * s * 2;
        float radius = r * s;
        float inner = radius - s;
        Identifier id = Identifier.fromNamespaceAndPath(FeaturedClient.MOD_ID, "round/" + key);
        NativeImage image = new NativeImage(size, size, true);
        try {
            for (int py = 0; py < size; py++) {
                for (int px = 0; px < size; px++) {
                    float dx = radius - (px + 0.5f);
                    float dy = radius - (py + 0.5f);
                    float d = (float) Math.sqrt(dx * dx + dy * dy);
                    float cover = clamp01(radius - d + 0.5f);
                    float value = switch (mode) {
                        case FILL -> cover;
                        case MASK -> 1f - cover;
                        case OUTLINE -> cover - clamp01(inner - d + 0.5f);
                    };
                    image.setPixel(px, py, Math.round(value * 255) << 24 | 0xFFFFFF);
                }
            }
            Minecraft.getInstance().getTextureManager().register(id, cc.serverlist.featured.compat.Compat.uploadTexture("serverlist " + key, image, false));
            TEXTURES.put(key, id);
            return id;
        } catch (Throwable t) {
            FeaturedClient.LOGGER.warn("Could not make corner texture {}: {}", key, t.toString());
            TEXTURES.put(key, null);
            return null;
        } finally {
            image.close();
        }
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(v, 1f);
    }
}
