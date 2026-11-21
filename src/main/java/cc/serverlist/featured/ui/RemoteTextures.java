package cc.serverlist.featured.ui;

import cc.serverlist.featured.FeaturedClient;
import cc.serverlist.featured.api.ServerListApi;
import cc.serverlist.featured.compat.Compat;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Images fetched over HTTP and turned into GPU textures.
 *
 * <p>A texture is requested by key the first time it is drawn (or prefetched) and comes back
 * {@code null} until it is ready. The bytes come from the disk cache or the API on the fetch
 * pool, the PNG is decoded on a decode thread, and only the upload happens on the render thread
 * — the only place a texture may be made — so a page of banners arriving doesn't stall a frame.
 * A URL that fails is remembered so a card never re-requests it every frame.
 *
 * <p>Banners are drawn smooth (linear filtering), since they are photographs scaled to whatever
 * the GUI scale makes of a card; icons stay nearest, since most favicons are pixel art. Once the
 * textures add up to more than {@link #BUDGET_BYTES}, the ones not drawn for a while are released;
 * the disk cache brings them back without a download.
 */
public final class RemoteTextures {
    /**
     * A ready texture, its pixel size (the banner's cover crop needs it) and its accent: the
     * colour a person would say the image "is", as ARGB, or 0 for an image with no colour in it.
     */
    public record Tex(Identifier id, int width, int height, int accent) {
    }

    private record Decoded(NativeImage image, int accent) {
    }

    private static final class Entry {
        final Tex tex;
        final long bytes;
        long usedAt;

        Entry(Tex tex, long bytes) {
            this.tex = tex;
            this.bytes = bytes;
            this.usedAt = System.currentTimeMillis();
        }
    }

    /** About 150 wide banners; a page on screen uses a fraction of that. */
    private static final long BUDGET_BYTES = 160L * 1024 * 1024;
    /** Nothing drawn in the last few seconds is released, however far over budget. */
    private static final long KEEP_RECENT_MS = 5_000;

    private static final Map<String, Entry> READY = new HashMap<>();
    private static final Set<String> PENDING = new HashSet<>();
    /** Keys that failed, and when: a failure is retried after a minute, since most are a slow moment, not a bad image. */
    private static final Map<String, Long> FAILED = new HashMap<>();
    private static final long RETRY_MS = 60_000;
    private static long totalBytes;

    private static final ExecutorService DECODE = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "serverlist-decode");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private RemoteTextures() {
    }

    /** The texture for {@code url}, or {@code null} while it is loading or after it failed. */
    public static Tex get(String key, String url) {
        return get(key, url, false);
    }

    /** As {@link #get(String, String)}; {@code smooth} draws it with linear filtering. */
    public static Tex get(String key, String url, boolean smooth) {
        Tex ready = ready(key);
        if (ready != null) {
            return ready;
        }
        if (failed(key) || !PENDING.add(key)) {
            return null;
        }
        ServerListApi.bytes(url)
                .thenApplyAsync(bytes -> decode(key, bytes), DECODE)
                .whenComplete((decoded, err) -> Minecraft.getInstance().execute(() -> {
                    PENDING.remove(key);
                    if (err != null) {
                        Throwable cause = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
                        if (!(cause instanceof Undecodable)) {
                            FeaturedClient.LOGGER.warn("Image {} not fetched: {}", url, cause.getMessage() != null ? cause.getMessage() : cause.toString());
                        }
                        FAILED.put(key, System.currentTimeMillis());
                    } else if (!install(key, decoded, smooth)) {
                        FAILED.put(key, System.currentTimeMillis());
                    }
                }));
        return null;
    }

    /** True while {@code key} is being fetched or decoded. */
    public static boolean loading(String key) {
        return PENDING.contains(key);
    }

    /** True while a recent failure stands; a minute on, the key may be tried again. */
    public static boolean failed(String key) {
        Long at = FAILED.get(key);
        if (at == null) {
            return false;
        }
        if (System.currentTimeMillis() - at > RETRY_MS) {
            FAILED.remove(key);
            return false;
        }
        return true;
    }

    /** Uploads already-fetched bytes (a ping's favicon) under {@code key}; false when undecodable. */
    public static boolean upload(String key, byte[] bytes) {
        try {
            return install(key, decode(key, bytes), false);
        } catch (Undecodable e) {
            return false;
        }
    }

    /** The texture if it is ready, without starting a load; drawing it counts as a use. */
    public static Tex ready(String key) {
        Entry e = READY.get(key);
        if (e == null) {
            return null;
        }
        e.usedAt = System.currentTimeMillis();
        return e.tex;
    }

    /** True once {@code key} is on the GPU, without counting as a use. */
    public static boolean has(String key) {
        return READY.containsKey(key);
    }

    /**
     * Releases the longest-unused textures while the total is over budget. Called each tick
     * by the page; anything drawn in the last few seconds stays whatever the total.
     */
    public static void trim() {
        if (totalBytes <= BUDGET_BYTES) {
            return;
        }
        long now = System.currentTimeMillis();
        List<Map.Entry<String, Entry>> old = new ArrayList<>();
        for (Map.Entry<String, Entry> e : READY.entrySet()) {
            if (now - e.getValue().usedAt > KEEP_RECENT_MS) {
                old.add(e);
            }
        }
        old.sort(Comparator.comparingLong(e -> e.getValue().usedAt));
        for (Map.Entry<String, Entry> e : old) {
            if (totalBytes <= BUDGET_BYTES) {
                break;
            }
            READY.remove(e.getKey());
            totalBytes -= e.getValue().bytes;
            Minecraft.getInstance().getTextureManager().release(e.getValue().tex.id());
        }
    }

    private static final class Undecodable extends RuntimeException {
        Undecodable(String message) {
            super(message, null, false, false);
        }
    }

    /** Bigger than any banner, icon or skin the page uses (the largest is 1280x512). */
    private static final int MAX_SIDE = 2048;
    private static final long MAX_PIXELS = 2048L * 1024;
    private static final int MAX_FILE = 8 << 20;

    /**
     * Reads the size from the PNG header before anything is decoded. A tiny file can claim
     * 16384x16384 and decoding it would take gigabytes, so the claim is checked first.
     */
    private static void checkSize(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < 24 || bytes.length > MAX_FILE) {
            throw new IOException("image is empty or too large (" + (bytes == null ? 0 : bytes.length) + " bytes)");
        }
        // PNG signature, then the IHDR chunk: length (4), "IHDR" (4), width (4), height (4).
        if ((bytes[0] & 0xFF) != 0x89 || bytes[1] != 'P' || bytes[2] != 'N' || bytes[3] != 'G'
                || bytes[12] != 'I' || bytes[13] != 'H' || bytes[14] != 'D' || bytes[15] != 'R') {
            throw new IOException("not a PNG");
        }
        long w = ((bytes[16] & 0xFFL) << 24) | ((bytes[17] & 0xFF) << 16) | ((bytes[18] & 0xFF) << 8) | (bytes[19] & 0xFF);
        long h = ((bytes[20] & 0xFFL) << 24) | ((bytes[21] & 0xFF) << 16) | ((bytes[22] & 0xFF) << 8) | (bytes[23] & 0xFF);
        if (w <= 0 || h <= 0 || w > MAX_SIDE || h > MAX_SIDE || w * h > MAX_PIXELS) {
            throw new IOException("image claims " + w + "x" + h + ", over the limit");
        }
    }

    /** Off the render thread: PNG to pixels, and the accent while they are in hand. */
    private static Decoded decode(String key, byte[] bytes) {
        try {
            checkSize(bytes);
            NativeImage image = NativeImage.read(bytes);
            return new Decoded(image, accentOf(image));
        } catch (IOException | RuntimeException t) {
            FeaturedClient.LOGGER.warn("Undecodable image for {} ({} bytes, starts {}): {}", key, bytes == null ? 0 : bytes.length,
                    bytes == null || bytes.length < 4 ? "?" : String.format("%02x%02x%02x%02x", bytes[0], bytes[1], bytes[2], bytes[3]), t.toString());
            throw new Undecodable(t.toString());
        }
    }

    /** On the render thread: pixels to a GPU texture, registered under an id made from the key. */
    private static boolean install(String key, Decoded decoded, boolean smooth) {
        NativeImage image = decoded.image();
        try {
            Identifier id = Identifier.fromNamespaceAndPath(FeaturedClient.MOD_ID,
                    "remote/" + key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9/._-]", "_"));
            AbstractTexture texture = Compat.uploadTexture("serverlist " + key, image, smooth);
            Minecraft.getInstance().getTextureManager().register(id, texture);
            Entry previous = READY.put(key, new Entry(new Tex(id, image.getWidth(), image.getHeight(), decoded.accent()),
                    (long) image.getWidth() * image.getHeight() * 4));
            if (previous != null) {
                totalBytes -= previous.bytes;
            }
            totalBytes += (long) image.getWidth() * image.getHeight() * 4;
            return true;
        } catch (Throwable t) {
            FeaturedClient.LOGGER.warn("Could not upload image for {}: {}", key, t.toString());
            return false;
        } finally {
            // The GPU has its copy; keeping the pixels too would double what every banner costs.
            image.close();
        }
    }


    /**
     * The image's dominant vivid colour: the hue most of its colourful pixels share, weighted
     * by how colourful each is, so a logo's brand colour wins over its dark backdrop, its black
     * outline and its white highlights even when those cover more pixels. Greys, near-blacks
     * and near-whites don't vote at all. Returns the winning hue's average colour, or the plain
     * average when nothing in the image has a hue.
     */
    private static int accentOf(NativeImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        int stepX = Math.max(1, w / 64);
        int stepY = Math.max(1, h / 64);
        final int bins = 24;
        double[] weight = new double[bins];
        double[] sumR = new double[bins];
        double[] sumG = new double[bins];
        double[] sumB = new double[bins];
        long plainR = 0, plainG = 0, plainB = 0;
        int plainN = 0;
        for (int y = 0; y < h; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                int p = image.getPixel(x, y);
                if ((p >>> 24) < 128) {
                    continue;
                }
                int r = (p >> 16) & 0xFF;
                int g = (p >> 8) & 0xFF;
                int b = p & 0xFF;
                plainR += r;
                plainG += g;
                plainB += b;
                plainN++;
                int max = Math.max(r, Math.max(g, b));
                int min = Math.min(r, Math.min(g, b));
                double sat = max == 0 ? 0 : (max - min) / (double) max;
                double val = max / 255.0;
                if (sat < 0.3 || val < 0.25) {
                    continue;
                }
                double hue;
                if (max == r) {
                    hue = ((g - b) / (double) (max - min) + 6) % 6;
                } else if (max == g) {
                    hue = (b - r) / (double) (max - min) + 2;
                } else {
                    hue = (r - g) / (double) (max - min) + 4;
                }
                int bin = (int) (hue / 6 * bins) % bins;
                double wgt = sat * val;
                weight[bin] += wgt;
                sumR[bin] += r * wgt;
                sumG[bin] += g * wgt;
                sumB[bin] += b * wgt;
            }
        }
        int best = 0;
        for (int i = 1; i < bins; i++) {
            if (weight[i] > weight[best]) {
                best = i;
            }
        }
        if (weight[best] > 0) {
            int r = (int) (sumR[best] / weight[best]);
            int g = (int) (sumG[best] / weight[best]);
            int b = (int) (sumB[best] / weight[best]);
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        if (plainN == 0) {
            return 0;
        }
        return 0xFF000000 | ((int) (plainR / plainN) << 16) | ((int) (plainG / plainN) << 8) | (int) (plainB / plainN);
    }
}
