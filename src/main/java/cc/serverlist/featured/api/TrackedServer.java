package cc.serverlist.featured.api;

import java.util.List;

/**
 * One server as serverlist.cc describes it, trimmed to what the page shows.
 *
 * <p>The numbers are the tracker's; {@code dayAgo} and {@code weekAgo} are its average player
 * count in the same hour a day and a week ago, or {@code -1} where it has no bucket yet.
 * {@code motd} is the last MOTD the tracker saw with its formatting codes stripped; the page
 * prefers the live ping's formatted text once it lands and only falls back to this before then.
 *
 * <p>{@code tags}, {@code color}, the banner paths, {@code website}, {@code discord}, {@code store}
 * and {@code description} are what the API knows
 * from outside the tracker (Lunar's mappings, laby's renders) and only come with
 * {@code /v1/featured}; a server that arrived through the full list for a search has none of
 * them — empty tags, colour {@code 0}, null banners — and the page falls back accordingly.
 *
 * <p>{@code cracked} is the tracker's login probe saying the server lets anyone in without a
 * Mojang account. Only the full list carries cracked servers at all; the featured document
 * leaves them out, so every record from it is {@code false}.
 */
public record TrackedServer(
        String slug,
        String name,
        String host,
        int port,
        String address,
        boolean online,
        int players,
        int maxPlayers,
        String version,
        String motd,
        String icon,
        int peakPlayers,
        int dayAgo,
        int weekAgo,
        long addedAt,
        List<String> tags,
        int color,
        String bannerCard,
        String bannerWide,
        String bannerHero,
        String website,
        String discord,
        String store,
        String description,
        boolean cracked
) {
}
