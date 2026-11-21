package cc.serverlist.featured.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The page as {@code /v1/featured} lays it out: the hero slot, the strips in order, and every
 * server any of them shows, most players first. Row membership and the hero are the API's
 * call, so they can change without a new jar.
 */
public record Featured(TrackedServer hero, List<Row> rows, List<TrackedServer> servers) {
    /**
     * One strip: its id ({@code top}, {@code rising}, a genre), the words above it, and the
     * cards loaded so far out of {@link #total()}. A row is as long as the servers that
     * qualify for it — thousands, for Top — and the page carries only its head; the screen
     * appends pages from {@code /v1/featured/rows/<id>} as the player scrolls towards the end.
     * The list is only touched on the render thread.
     */
    public static final class Row {
        private final String id;
        private final String title;
        private final String subtitle;
        private final int total;
        private final List<TrackedServer> servers = new ArrayList<>();
        private final Set<String> slugs = new HashSet<>();
        /** How many records have been received, duplicates included: the next page's offset. */
        private int fetched;
        /** A page is on its way; the screen sets and clears this so it never asks twice. */
        public boolean loading;
        /** After a failed page, no retry before this clock time, so a dead API isn't hit every frame. */
        public long retryAt;

        Row(String id, String title, String subtitle, int total, List<TrackedServer> first) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
            this.total = Math.max(total, first.size());
            append(first);
        }

        /** A row the client made itself — complete as given, nothing to page in. */
        public static Row local(String id, String title, String subtitle, List<TrackedServer> servers) {
            return new Row(id, title, subtitle, servers.size(), servers);
        }

        public String id() {
            return id;
        }

        public String title() {
            return title;
        }

        public String subtitle() {
            return subtitle;
        }

        public int total() {
            return total;
        }

        /** The cards loaded so far, in row order. Read-only; pages arrive through {@link #append}. */
        public List<TrackedServer> servers() {
            return Collections.unmodifiableList(servers);
        }

        /** Where the next page starts. */
        public int nextOffset() {
            return fetched;
        }

        /** True once everything the row has is here. */
        public boolean exhausted() {
            return fetched >= total;
        }

        /**
         * Adds a page. A server already here (the row moved under us between pages) is skipped
         * but still counts towards the offset, and an empty page means the row is done.
         */
        public void append(List<TrackedServer> page) {
            if (page.isEmpty()) {
                fetched = total;
                return;
            }
            fetched += page.size();
            for (TrackedServer s : page) {
                if (slugs.add(s.slug())) {
                    servers.add(s);
                }
            }
        }
    }
}
