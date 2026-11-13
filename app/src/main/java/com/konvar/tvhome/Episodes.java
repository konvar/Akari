package com.konvar.tvhome;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * A show's episodes from Cinemeta, and the rules built on them that every tracking provider shares (the ideas come
 * from Nuvio's CwEpisodesLeft and ContinueWatchingAiringRules):
 * <ul>
 * <li>"N left": aired episodes from the card's episode to the end, so a show you are behind on says how far.</li>
 * <li>Next up: the first aired episode after the furthest one watched.</li>
 * <li>Upcoming: the first episode after the furthest one watched that has not aired yet.</li>
 * <li>A show is finished only when every aired episode is watched. A new episode airing makes it unfinished again
 * at once, so the show comes back to Continue Watching on its air day instead of being dropped.</li>
 * </ul>
 * Specials (season 0) never count.
 */
final class Episodes {

    private static final String TAG = "TvHomeEpisodes";
    private static final long TTL = 3 * 3600 * 1000L;
    private static final long DAY = 24 * 3600 * 1000L;

    static final class Ep {
        final int season, episode;
        /** Air time in ms; 0 when Cinemeta has no date. */
        final long released;
        final String title;

        Ep(int season, int episode, long released, String title) {
            this.season = season;
            this.episode = episode;
            this.released = released;
            this.title = title;
        }

        String key() {
            return season + ":" + episode;
        }

        boolean after(int s, int e) {
            return season > s || (season == s && episode > e);
        }

        boolean atOrAfter(int s, int e) {
            return season > s || (season == s && episode >= e);
        }

        JSONObject json() {
            try {
                return new JSONObject().put("season", season).put("number", episode).put("title", title == null ? "" : title);
            } catch (Exception e) {
                return new JSONObject();
            }
        }
    }

    /** One show's list, in airing order, with the time it was fetched. */
    private static final class Cached {
        final long at;
        final List<Ep> list;

        Cached(long at, List<Ep> list) {
            this.at = at;
            this.list = list;
        }
    }

    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();

    private Episodes() {
    }

    /** The show's regular episodes in order; empty when Cinemeta does not know it (never null). */
    static List<Ep> list(Context c, String imdb) {
        Cached hit = CACHE.get(imdb);
        if (hit != null && System.currentTimeMillis() - hit.at < TTL) return hit.list;
        List<Ep> out = new ArrayList<>();
        try {
            String addon = c.getString(R.string.addon_url);
            JSONObject root = new JSONObject(new String(Net.get(addon + "/meta/series/" + imdb + ".json"), StandardCharsets.UTF_8));
            JSONObject meta = root.optJSONObject("meta");
            JSONArray videos = meta != null ? meta.optJSONArray("videos") : null;
            Set<String> seen = new HashSet<>();
            for (int i = 0; videos != null && i < videos.length(); i++) {
                JSONObject v = videos.optJSONObject(i);
                if (v == null) continue;
                int s = v.optInt("season", 0), e = v.optInt("episode", v.optInt("number", 0));
                if (s <= 0 || e <= 0 || !seen.add(s + ":" + e)) continue;
                String title = v.optString("name", v.optString("title", ""));
                out.add(new Ep(s, e, Trakt.time(v.optString("released", v.optString("firstAired", ""))), title));
            }
            out.sort((a, b) -> a.season != b.season ? Integer.compare(a.season, b.season) : Integer.compare(a.episode, b.episode));
        } catch (Exception e) {
            Log.w(TAG, "episodes of " + imdb + ": " + e);
            // Keep an older list rather than nothing when Cinemeta does not answer.
            if (hit != null) return hit.list;
        }
        out = Collections.unmodifiableList(out);
        CACHE.put(imdb, new Cached(System.currentTimeMillis(), out));
        return out;
    }

    /**
     * Whether an episode is out. Dated: its date has passed. Undated: only when a later episode has aired (an undated
     * episode after the last aired one is not out yet, as in Nuvio).
     */
    static boolean aired(List<Ep> list, Ep ep, long now) {
        if (ep.released > 0) return ep.released <= now;
        for (int i = list.size() - 1; i >= 0; i--) {
            Ep o = list.get(i);
            if (o.released > 0 && o.released <= now) return o.after(ep.season, ep.episode);
        }
        // No dates at all: the whole list counts as out.
        for (Ep o : list) if (o.released > 0) return false;
        return true;
    }

    /** Aired episodes from (season, episode) to the end, that one included; 0 when unknown. */
    static int leftFrom(List<Ep> list, int season, int episode, long now) {
        int n = 0;
        for (Ep ep : list) if (ep.atOrAfter(season, episode) && aired(list, ep, now)) n++;
        return n;
    }

    /** The furthest episode in the watched set ("s:e" keys), or null. */
    static Ep furthest(List<Ep> list, Set<String> watched) {
        Ep best = null;
        for (Ep ep : list) if (watched.contains(ep.key())) best = ep;
        return best;
    }

    /** The first episode after (season, episode); with (0, 0) the very first one. */
    static Ep after(List<Ep> list, int season, int episode) {
        for (Ep ep : list) if (ep.after(season, episode)) return ep;
        return null;
    }

    /** Every aired episode watched (and at least one aired). */
    static boolean finished(List<Ep> list, Set<String> watched, long now) {
        boolean any = false;
        for (Ep ep : list) {
            if (!aired(list, ep, now)) continue;
            any = true;
            if (!watched.contains(ep.key())) return false;
        }
        return any;
    }

    // ---- "N left" on Continue Watching ----

    /** Fills {@link Catalog.Item#episodesLeft} on the show cards of a Continue Watching list, in parallel. */
    static void fillLeft(Context c, List<Catalog.Item> items) {
        if (items == null || items.isEmpty()) return;
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Future<?>> jobs = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Catalog.Item it : items) {
            if (!"series".equals(it.type) || it.season <= 0 || it.id == null) continue;
            jobs.add(pool.submit(() -> {
                List<Ep> list = list(c, it.id);
                it.episodesLeft = leftFrom(list, it.season, it.episode, now);
            }));
        }
        for (Future<?> f : jobs) {
            try {
                f.get(20, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        }
        pool.shutdownNow();
    }

    // ---- Next up and Upcoming for providers other than Trakt ----

    /** What a provider knows about one show you watch. */
    static final class Show {
        final String imdb;
        String title = "";
        /** Last watched, ms (0 unknown). */
        long lastWatched;
        /** Watched episodes ("s:e"); null when the provider only knows the next episode. */
        Set<String> watched;
        /** The provider's next episode (Simkl's "next to watch"); 0 when unknown. */
        int nextSeason, nextEpisode;

        Show(String imdb) {
            this.imdb = imdb;
        }
    }

    /** The result of {@link #resolve}: cards for both rows and the shows that are fully watched. */
    static final class Result {
        final List<Catalog.Item> nextUp = new ArrayList<>();
        final List<Catalog.Item> upcoming = new ArrayList<>();
        final Set<String> finished = Collections.synchronizedSet(new HashSet<>());
    }

    /** The most recent shows looked at, so a long history does not mean hundreds of requests. */
    private static final int MAX_SHOWS = 60;

    /**
     * Next up, Upcoming (the next 31 days) and finished shows, for the shows a provider reports. {@code skip}: shows
     * already on Continue Watching with a paused episode; they get no second card but still count for Upcoming.
     */
    static Result resolve(Context c, String pkg, List<Show> shows, Set<String> skip) {
        Result out = new Result();
        List<Show> recent = new ArrayList<>(shows);
        recent.sort((a, b) -> Long.compare(b.lastWatched, a.lastWatched));
        if (recent.size() > MAX_SHOWS) recent = recent.subList(0, MAX_SHOWS);
        long now = System.currentTimeMillis();
        SimpleDateFormat when = new SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault());
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Catalog.Item[]>> jobs = new ArrayList<>();
        for (Show sh : recent) {
            jobs.add(pool.submit(() -> {
                List<Ep> list = list(c, sh.imdb);
                if (list.isEmpty()) return null;
                Ep from;
                if (sh.watched != null) {
                    Ep last = furthest(list, sh.watched);
                    if (last == null) return null;
                    if (finished(list, sh.watched, now)) out.finished.add(sh.imdb);
                    from = after(list, last.season, last.episode);
                } else if (sh.nextSeason > 0) {
                    from = null;
                    for (Ep ep : list) {
                        if (ep.atOrAfter(sh.nextSeason, sh.nextEpisode)) {
                            from = ep;
                            break;
                        }
                    }
                } else {
                    return null;
                }
                if (from == null) return null;
                Catalog.Item[] cards = new Catalog.Item[2];
                JSONObject media = new JSONObject().put("title", sh.title == null ? "" : sh.title)
                        .put("ids", new JSONObject().put("imdb", sh.imdb));
                if (aired(list, from, now)) {
                    // Aired and not watched: Next up, even if the show was marked finished before it came out.
                    out.finished.remove(sh.imdb);
                    if (!skip.contains(sh.imdb)) {
                        Catalog.Item it = Trakt.base(media, sh.imdb, false);
                        Trakt.episode(it, from.json());
                        it.kind = "cw";
                        it.airs = from.released;
                        it.order = sh.lastWatched;
                        it.episodesLeft = leftFrom(list, from.season, from.episode, now);
                        cards[0] = it;
                    }
                    // Upcoming shows what is still to come after the aired ones.
                    Ep next = null;
                    for (Ep ep : list) {
                        if (ep.after(from.season, from.episode) && !aired(list, ep, now)) {
                            next = ep;
                            break;
                        }
                    }
                    from = next;
                }
                if (from != null && from.released > now && from.released - now <= 31 * DAY) {
                    Catalog.Item it = Trakt.base(media, sh.imdb, false);
                    Trakt.episode(it, from.json());
                    it.subtitle = it.subtitle + "  ·  " + when.format(new java.util.Date(from.released));
                    it.order = -from.released;
                    it.airs = from.released;
                    it.kind = "upcoming";
                    cards[1] = it;
                }
                return cards;
            }));
        }
        for (Future<Catalog.Item[]> f : jobs) {
            try {
                Catalog.Item[] cards = f.get(30, TimeUnit.SECONDS);
                if (cards == null) continue;
                if (cards[0] != null) out.nextUp.add(cards[0]);
                if (cards[1] != null) out.upcoming.add(cards[1]);
            } catch (Exception e) {
                Log.w(TAG, "next up: " + e);
            }
        }
        pool.shutdownNow();
        out.upcoming.sort((a, b) -> Long.compare(b.order, a.order));
        Log.i(TAG, recent.size() + " shows: " + out.nextUp.size() + " next up, " + out.upcoming.size() + " upcoming, "
                + out.finished.size() + " finished");
        return out;
    }

    /** Names for cards that only carry an IMDb id (Nuvio and MDBList store few or no titles). */
    static void fillNames(Context c, List<Catalog.Item> items) {
        String addon = c.getString(R.string.addon_url);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Future<?>> jobs = new ArrayList<>();
        for (Catalog.Item it : items) {
            if (it.name != null && !it.name.isEmpty() && !it.name.equals(it.id)) continue;
            jobs.add(pool.submit(() -> {
                try {
                    JSONObject meta = new JSONObject(new String(Net.get(addon + "/meta/" + it.type + "/" + it.id + ".json"),
                            StandardCharsets.UTF_8)).optJSONObject("meta");
                    if (meta != null) {
                        it.name = meta.optString("name", it.id);
                        String year = meta.optString("year", meta.optString("releaseInfo", ""));
                        if (!year.isEmpty()) it.year = year.length() > 4 ? year.substring(0, 4) : year;
                    }
                } catch (Exception ignored) {
                }
                if (it.name == null || it.name.isEmpty()) it.name = it.id;
            }));
        }
        for (Future<?> f : jobs) {
            try {
                f.get(20, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        }
        pool.shutdownNow();
    }
}
