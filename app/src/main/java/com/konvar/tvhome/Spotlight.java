package com.konvar.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Spotlight (the idea is Nuvio's Scout): a row of tiles on Home (Surprise me, Picked for you, moods, decades, hidden gems) that open
 * TMDB grids shaped by your taste, and, with a Gemini key, "Ask Spotlight" in your own words.
 * <p>
 * Without AI everything still works: your taste is a weight per TMDB genre, learned on the TV from your ratings,
 * Continue Watching and watched titles; moods are genre recipes ordered by that taste; titles you watched are left
 * out. With a Gemini key (Settings → Accounts) "Picked for you" is written by Gemini from the titles you rated, and
 * the Ask tile takes a question ("a cozy 90s mystery"). Any Gemini failure falls back to the no-AI picks.
 */
final class Spotlight {

    private static final String TAG = "TvHomeSpotlight";
    static final String ROW = "Spotlight";

    /** A tile: id, name, line, movie genres, show genres ("|" any of, "," all of), extra discover filters. */
    private static final String[][] SLICES = {
            {"picks", "Picked for you", "From what you watch and rate", "", "", ""},
            {"feelgood", "Feel-good", "Light, warm, funny", "35|10751|10749", "35|10751", "&vote_average.gte=6.5"},
            {"tense", "Edge of your seat", "Thrillers and action", "53|28", "10759|80", "&vote_average.gte=6.3"},
            {"mind", "Mind-bending", "Sci-fi and mysteries", "878|9648", "10765|9648", "&vote_average.gte=6.5"},
            {"dark", "Dark & gritty", "Crime and hard drama", "80,18", "80,18", "&vote_average.gte=7"},
            {"laugh", "Laugh out loud", "Comedies", "35", "35", "&vote_average.gte=6.3"},
            {"scary", "Scary night", "Horror", "27", "9648", "&vote_average.gte=6"},
            {"cozy", "Cozy night in", "Animation and family", "16|10751", "16|10751", "&vote_average.gte=6.8"},
            {"epic", "Epic adventures", "Big journeys and fantasy", "12|14", "10759|10765", "&vote_average.gte=6.5"},
            {"gems", "Hidden gems", "Loved, little known", "", "", "&vote_average.gte=7.4&vote_count.gte=150&vote_count.lte=2500"},
            {"80s", "The 80s", "1980 – 1989", "", "", "@1980"},
            {"90s", "The 90s", "1990 – 1999", "", "", "@1990"},
            {"00s", "The 2000s", "2000 – 2009", "", "", "@2000"},
            {"10s", "The 2010s", "2010 – 2019", "", "", "@2010"},
    };

    /**
     * Spotlight's own threads: its grids and look-ups never wait behind Home's Trakt and catalog loading, which keeps
     * the shared queues busy for minutes.
     */
    static final java.util.concurrent.ExecutorService POOL = java.util.concurrent.Executors.newFixedThreadPool(6);

    private Spotlight() {
    }

    // ---- the Home row ----

    /** The tiles in taste order: Ask (with a key), Surprise me, Picked for you, then moods your taste ranks first. */
    static List<Catalog.Item> tiles(Context c) {
        Map<Integer, Double> taste = tasteCached(c);
        List<String[]> moods = new ArrayList<>();
        for (String[] s : SLICES) if (!s[3].isEmpty()) moods.add(s);
        moods.sort((a, b) -> Double.compare(score(b, taste), score(a, taste)));
        List<Catalog.Item> out = new ArrayList<>();
        if (Keys.has(Keys.GEMINI)) out.add(tile("ask", "Ask Spotlight", "Say what you feel like"));
        out.add(tile("surprise", "Surprise me", "One title for tonight"));
        out.add(tile("picks", SLICES[0][1], Keys.has(Keys.GEMINI) ? "Chosen by Gemini for you" : SLICES[0][2]));
        for (String[] s : moods) out.add(tile(s[0], s[1], s[2]));
        for (String[] s : SLICES) if (s[3].isEmpty() && !"picks".equals(s[0])) out.add(tile(s[0], s[1], s[2]));
        return out;
    }

    private static Catalog.Item tile(String id, String name, String line) {
        Catalog.Item it = new Catalog.Item();
        it.type = "spotlight";
        it.id = id;
        it.name = name;
        it.subtitle = line;
        // Its picture: the tile covers Nuvio's Scout ships (assets/spotlight).
        it.thumb = "asset://spotlight/" + id + ".webp";
        return it;
    }

    private static double score(String[] slice, Map<Integer, Double> taste) {
        double s = 0;
        for (String g : (slice[3] + "|" + slice[4]).split("[|,]")) {
            if (g.isEmpty()) continue;
            Double w = taste.get(Integer.parseInt(g));
            if (w != null) s += w;
        }
        return s;
    }

    /** OK on a tile: Surprise opens one title's page, Ask asks the question, the rest open a grid. */
    static boolean open(Context c, Catalog.Item it) {
        if (!"spotlight".equals(it.type)) return false;
        if ("surprise".equals(it.id)) {
            Net.API.execute(() -> {
                Catalog.Item pick = surprise(c);
                Net.MAIN.post(() -> {
                    if (pick != null) c.startActivity(DetailsActivity.intent(c, pick));
                    else android.widget.Toast.makeText(c, "Nothing found right now", android.widget.Toast.LENGTH_SHORT).show();
                });
            });
            return true;
        }
        if ("ask".equals(it.id)) {
            // The search page in its Ask mode: same field, keyboard, voice and result rows as Search.
            c.startActivity(new Intent(c, SearchActivity.class).putExtra("ask", true));
            return true;
        }
        c.startActivity(new Intent(c, GridActivity.class).putExtra("mode", "spotlight").putExtra("source", it.id)
                .putExtra("title", it.name));
        return true;
    }

    // ---- grids ----

    /** One page of a tile's grid, movies and shows mixed, without titles you watched. */
    static List<Catalog.Item> page(Context c, String slice, int page) throws Exception {
        if ("picks".equals(slice) && page == 1 && Keys.has(Keys.GEMINI)) {
            List<Catalog.Item> ai = geminiPicks(c);
            if (!ai.isEmpty()) return ai;
        }
        if ("picks".equals(slice) && Keys.has(Keys.GEMINI) && page > 1) page -= 1;
        String[] s = find(slice);
        if (s == null) return new ArrayList<>();
        Map<Integer, Double> taste = tasteCached(c);
        String movieQ = query(s, true, taste), tvQ = query(s, false, taste);
        int p = page;
        java.util.concurrent.Future<List<Catalog.Item>> movies = POOL.submit(() -> Tmdb.discoverQuery(true, movieQ, p));
        java.util.concurrent.Future<List<Catalog.Item>> shows = POOL.submit(() -> Tmdb.discoverQuery(false, tvQ, p));
        return unwatched(interleave(movies.get(), shows.get()));
    }

    private static String[] find(String id) {
        for (String[] s : SLICES) if (s[0].equals(id)) return s;
        return null;
    }

    /** The discover filters for a tile; "picks" and the decades use your top genres. */
    private static String query(String[] s, boolean movie, Map<Integer, Double> taste) {
        // Hidden gems set their own vote range; TMDB refuses (400) a filter given twice.
        StringBuilder q = new StringBuilder("&sort_by=popularity.desc");
        if (!s[5].contains("vote_count.gte")) q.append("&vote_count.gte=200");
        String genres = movie ? s[3] : s[4];
        if (genres.isEmpty() && !"gems".equals(s[0])) genres = topGenres(taste, movie);
        if (!genres.isEmpty()) q.append("&with_genres=").append(genres);
        String extra = s[5];
        if (extra.startsWith("@")) {
            int y = Integer.parseInt(extra.substring(1));
            String from = movie ? "primary_release_date" : "first_air_date";
            q.append("&").append(from).append(".gte=").append(y).append("-01-01&").append(from).append(".lte=")
                    .append(y + 9).append("-12-31&vote_average.gte=6.5");
        } else {
            q.append(extra);
        }
        if ("picks".equals(s[0])) q.append("&vote_average.gte=7");
        return q.toString();
    }

    /** Your three strongest genres as an "any of" filter; empty before Spotlight has learned anything. */
    private static String topGenres(Map<Integer, Double> taste, boolean movie) {
        List<Map.Entry<Integer, Double>> list = new ArrayList<>(taste.entrySet());
        list.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        StringBuilder out = new StringBuilder();
        int n = 0;
        for (Map.Entry<Integer, Double> e : list) {
            if (e.getValue() <= 0 || !Tmdb.genreFits(e.getKey(), movie)) continue;
            if (out.length() > 0) out.append('|');
            out.append(e.getKey());
            if (++n == 3) break;
        }
        return out.toString();
    }

    private static List<Catalog.Item> interleave(List<Catalog.Item> a, List<Catalog.Item> b) {
        List<Catalog.Item> out = new ArrayList<>();
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            if (i < a.size()) out.add(a.get(i));
            if (i < b.size()) out.add(b.get(i));
        }
        return out;
    }

    private static List<Catalog.Item> unwatched(List<Catalog.Item> items) {
        List<Catalog.Item> out = new ArrayList<>();
        for (Catalog.Item it : items) if (!Trakt.isWatched(it.id)) out.add(it);
        return out;
    }

    /** A random good title from your picks (a random one of the first few pages). */
    static Catalog.Item surprise(Context c) {
        try {
            Random r = new Random();
            for (int attempt = 0; attempt < 3; attempt++) {
                List<Catalog.Item> got = page(c, "picks", 1 + r.nextInt(5) + (Keys.has(Keys.GEMINI) ? 1 : 0));
                if (!got.isEmpty()) return got.get(r.nextInt(Math.min(got.size(), 12)));
            }
        } catch (Exception e) {
            Log.w(TAG, "surprise: " + e);
        }
        return null;
    }

    // ---- taste ----

    /** Genre weights learned on the TV; recomputed in the background at most every 12 hours. */
    static Map<Integer, Double> tasteCached(Context c) {
        SharedPreferences p = c.getSharedPreferences("spotlight", Context.MODE_PRIVATE);
        Map<Integer, Double> out = new HashMap<>();
        try {
            JSONObject o = new JSONObject(p.getString("taste", "{}"));
            java.util.Iterator<String> k = o.keys();
            while (k.hasNext()) {
                String g = k.next();
                out.put(Integer.parseInt(g), o.optDouble(g));
            }
        } catch (Exception ignored) {
        }
        if (System.currentTimeMillis() - p.getLong("taste_at", 0) > 12 * 3600 * 1000L && LEARNING.compareAndSet(false, true)) {
            Net.IO.execute(() -> {
                try {
                    learn(c.getApplicationContext());
                } finally {
                    LEARNING.set(false);
                }
            });
        }
        return out;
    }

    private static final java.util.concurrent.atomic.AtomicBoolean LEARNING = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Ratings count most (7–10 up, 1–4 down), then what is on Continue Watching, then a sample of watched titles.
     * Each title's TMDB genres are looked up once and kept.
     */
    private static void learn(Context c) {
        Map<String, Double> seeds = new HashMap<>();
        for (Map.Entry<String, Integer> e : new HashMap<>(Trakt.myRatings).entrySet()) {
            String k = e.getKey();
            int rating = e.getValue();
            if (!(k.startsWith("m:") || (k.startsWith("s:") && k.indexOf(':', 2) < 0))) continue;
            double w = rating >= 7 ? rating - 5 : rating <= 4 ? -2 : 0;
            if (w != 0) seeds.put(k, w);
        }
        for (Catalog.Item it : new ArrayList<>(Trakt.lastContinueWatching)) {
            seeds.merge(("movie".equals(it.type) ? "m:" : "s:") + it.id, 2.0, Double::sum);
        }
        int sample = 0;
        for (String id : new ArrayList<>(Trakt.watchedIds)) {
            if (sample++ >= 40) break;
            // Watched titles do not say whether they are movies; ask TMDB both ways below.
            seeds.merge("?:" + id, 1.0, Double::sum);
        }
        SharedPreferences p = c.getSharedPreferences("spotlight", Context.MODE_PRIVATE);
        SharedPreferences genres = c.getSharedPreferences("spotlight_genres", Context.MODE_PRIVATE);
        Map<Integer, Double> taste = new HashMap<>();
        int lookups = 0;
        for (Map.Entry<String, Double> e : seeds.entrySet()) {
            String imdb = e.getKey().substring(2);
            String known = genres.getString(imdb, null);
            if (known == null) {
                if (lookups++ >= 60) continue;
                known = Tmdb.genreIds(imdb);
                genres.edit().putString(imdb, known).apply();
            }
            for (String g : known.split(",")) {
                if (g.isEmpty()) continue;
                taste.merge(Integer.parseInt(g), e.getValue(), Double::sum);
            }
        }
        JSONObject o = new JSONObject();
        try {
            for (Map.Entry<Integer, Double> e : taste.entrySet()) o.put(String.valueOf(e.getKey()), e.getValue());
        } catch (Exception ignored) {
        }
        p.edit().putString("taste", o.toString()).putLong("taste_at", System.currentTimeMillis()).apply();
        Log.i(TAG, "taste from " + seeds.size() + " titles: " + o);
    }

    // ---- Gemini (optional) ----

    private static final String GEMINI_API = "https://generativelanguage.googleapis.com/v1beta/models/";
    /** Flash-Lite (Google's newest), with thinking off: quick answers that stay in the free tier. */
    private static final String GEMINI_MODEL = "gemini-flash-lite-latest";

    /** Titles you rated highly, by name, for the prompts (names come from the cards Spotlight already has). */
    private static String likedTitles() {
        Set<String> names = new HashSet<>();
        for (Catalog.Item it : new ArrayList<>(Trakt.lastContinueWatching)) {
            if (it.name != null && !it.name.isEmpty() && !it.name.startsWith("tt")) names.add(it.name);
            if (names.size() >= 25) break;
        }
        return String.join("; ", names);
    }

    private static List<Catalog.Item> geminiPicks(Context c) {
        String prompt = "You recommend movies and TV shows. The viewer is currently watching: " + likedTitles()
                + ". Suggest 24 titles they have probably not seen that fit their taste, a mix of movies and shows, "
                + "well reviewed, not only the most famous ones.";
        // Picked for you is asked again at most every 12 hours.
        return gemini(c, "picks", prompt, 12 * 3600 * 1000L);
    }

    /** "Ask Spotlight": the question, with the viewer's titles as context. */
    static List<Catalog.Item> ask(Context c, String question) {
        String prompt = "You recommend movies and TV shows. The viewer asks: \"" + question.replace("\"", "'")
                + "\". For context, they are watching: " + likedTitles()
                + ". Suggest up to 24 titles that answer the request (movies, shows or both, as asked).";
        // The same question (from the recent ones) reuses its answer for a day instead of asking Gemini again.
        List<Catalog.Item> out = gemini(c, "q:" + question.trim().toLowerCase(java.util.Locale.ROOT), prompt, 24 * 3600 * 1000L);
        if (out.isEmpty()) {
            // No key, or Gemini failed: TMDB's own search for the words.
            try {
                out = Tmdb.search(question);
            } catch (Exception e) {
                Log.w(TAG, "ask fallback: " + e);
            }
        }
        return out;
    }

    /**
     * Gemini's picks for a prompt as cards. The answer (its list of titles) is kept under {@code key} for
     * {@code keepMs}, so asking again within that time costs no Gemini request; only the TMDB look-ups run again
     * (they come from the HTTP cache). Empty on any failure.
     */
    private static List<Catalog.Item> gemini(Context c, String key, String prompt, long keepMs) {
        List<Catalog.Item> out = new ArrayList<>();
        if (!Keys.has(Keys.GEMINI)) return out;
        SharedPreferences saved = c.getSharedPreferences("spotlight_answers", Context.MODE_PRIVATE);
        try {
            String text = null;
            JSONObject hit = new JSONObject(saved.getString(key, "{}"));
            if (System.currentTimeMillis() - hit.optLong("at") < keepMs) text = hit.optString("text", null);
            if (text == null) {
                text = askGemini(prompt);
                if (text == null) return out;
                saved.edit().putString(key, new JSONObject().put("at", System.currentTimeMillis()).put("text", text)
                        .toString()).apply();
                tidy(saved);
            } else {
                Log.i(TAG, "answer reused for " + key);
            }
            JSONArray picks = new JSONArray(text);
            Log.i(TAG, "Gemini named " + picks.length() + " titles for " + key);
            List<java.util.concurrent.Future<Catalog.Item>> jobs = new ArrayList<>();
            for (int i = 0; i < picks.length(); i++) {
                JSONObject t = picks.getJSONObject(i);
                jobs.add(POOL.submit(() -> Tmdb.findTitle(t.optString("title"), t.optInt("year", 0),
                        !"show".equals(t.optString("type")))));
            }
            Set<String> seen = new HashSet<>();
            for (java.util.concurrent.Future<Catalog.Item> f : jobs) {
                try {
                    Catalog.Item it = f.get(20, java.util.concurrent.TimeUnit.SECONDS);
                    if (it != null && seen.add(it.id) && !Trakt.isWatched(it.id)) out.add(it);
                } catch (Exception ignored) {
                }
            }
            Log.i(TAG, out.size() + " found on TMDB (watched ones left out)");
        } catch (Exception e) {
            Log.w(TAG, "Gemini: " + e);
        }
        return out;
    }

    /** Gemini's raw answer (a JSON array of {title, year, type}); null when it did not answer. */
    private static String askGemini(String prompt) throws Exception {
        JSONObject schema = new JSONObject("{\"type\":\"ARRAY\",\"items\":{\"type\":\"OBJECT\",\"properties\":{"
                + "\"title\":{\"type\":\"STRING\"},\"year\":{\"type\":\"INTEGER\"},"
                + "\"type\":{\"type\":\"STRING\",\"enum\":[\"movie\",\"show\"]}},\"required\":[\"title\",\"type\"]}}");
        JSONObject config = new JSONObject().put("responseMimeType", "application/json")
                .put("responseSchema", schema).put("temperature", 0.9)
                // No thinking: a list of titles does not need it, and answers come faster.
                .put("thinkingConfig", new JSONObject().put("thinkingBudget", 0));
        JSONObject body = new JSONObject()
                .put("contents", new JSONArray().put(new JSONObject().put("parts",
                        new JSONArray().put(new JSONObject().put("text", prompt)))))
                .put("generationConfig", config);
        Map<String, String> h = new HashMap<>();
        h.put("Content-Type", "application/json");
        String url = GEMINI_API + GEMINI_MODEL + ":generateContent?key=" + Keys.get(Keys.GEMINI);
        Net.Response r = Net.json("POST", url, h, body.toString());
        if (r.code == 400) {
            // A model version that does not take a thinking budget: ask again without it.
            config.remove("thinkingConfig");
            r = Net.json("POST", url, h, body.toString());
        }
        if (r.code != 200) {
            Log.w(TAG, "Gemini HTTP " + r.code);
            return null;
        }
        return new JSONObject(r.body).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text");
    }

    /** Keeps the saved answers to the 40 newest. */
    private static void tidy(SharedPreferences saved) {
        Map<String, ?> all = saved.getAll();
        if (all.size() <= 40) return;
        List<Map.Entry<String, ?>> list = new ArrayList<>(all.entrySet());
        list.sort((x, y) -> Long.compare(at(x.getValue()), at(y.getValue())));
        SharedPreferences.Editor e = saved.edit();
        for (int i = 0; i < list.size() - 40; i++) e.remove(list.get(i).getKey());
        e.apply();
    }

    private static long at(Object json) {
        try {
            return new JSONObject(String.valueOf(json)).optLong("at");
        } catch (Exception e) {
            return 0;
        }
    }
}
