package cc.serverlist.featured.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Text helpers for tracker data, and the genre fallback for servers the API knows nothing about.
 *
 * <p>Genres, names and colours come from {@code /v1/featured} now; the keyword pass here only
 * labels a search result that arrived through the full list without any of that.
 */
public final class Categories {
    private static final Pattern FORMATTING = Pattern.compile("§#[0-9a-fA-F]{6}|§.");
    private static final Pattern SPACES = Pattern.compile("\s+");

    /** Words in the name, MOTD or hostname that place a server, and the chip each earns. */
    private static final Map<String, String> KEYWORDS = new LinkedHashMap<>();

    static {
        for (String w : List.of("smp", "survival", "lifesteal", "vanilla", "towny", "earth")) {
            KEYWORDS.put(w, "SMP");
        }
        for (String w : List.of("minigame", "mini game", "bedwars", "bed wars", "skywars", "murder mystery")) {
            KEYWORDS.put(w, "Minigames");
        }
        for (String w : List.of("pvp", "practice", "duels", "kitmap", "anarchy", "factions", "hcf")) {
            KEYWORDS.put(w, "PvP");
        }
        for (String w : List.of("skyblock", "oneblock", "one block")) {
            KEYWORDS.put(w, "Skyblock");
        }
        for (String w : List.of("prison", "gens")) {
            KEYWORDS.put(w, "Prison");
        }
    }

    private Categories() {
    }

    /** The chips for a server the API gave no tags: whatever its own words say it is. */
    public static List<String> keywordTags(TrackedServer s) {
        String hay = (s.name() + " " + s.motd() + " " + s.host()).toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : KEYWORDS.entrySet()) {
            if (hay.contains(e.getKey()) && !out.contains(e.getValue())) {
                out.add(e.getValue());
            }
        }
        return out;
    }

    /** Strips legacy formatting codes and collapses whitespace, so tracker text renders plainly. */
    public static String clean(String s) {
        if (s == null) {
            return "";
        }
        return SPACES.matcher(FORMATTING.matcher(s).replaceAll("")).replaceAll(" ").trim();
    }
}
