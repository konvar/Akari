package com.konvar.tvhome;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a spoken "play …" request into a title, and an episode for shows, then starts it in the content app.
 * English and Romanian phrasing: "play the matrix", "play episode 15 from From", "play season 2 episode 3 of
 * Fargo", "play latest episode of Silicon Valley", "pune ultimul episod din Silicon Valley".
 */
final class VoicePlay {

    private static final String TAG = "TvHomeVoice";

    static final class Request {
        String title;
        int season = -1;
        int episode = -1;
        boolean latest;

        @Override
        public String toString() {
            return "'" + title + "' s=" + season + " e=" + episode + (latest ? " latest" : "");
        }
    }

    private static final Pattern VERB = Pattern.compile(
            "^(?:ok google[, ]*|hey google[, ]*)?(?:please )?(?:play|watch|start|resume|put on|"
                    + "pune|porneste|reda|ruleaza|da drumul la|vreau sa vad|arata-mi|deschide)\\s+(.+)$");
    private static final Pattern TRAILING_APP = Pattern.compile(
            "\\s+(?:on|in|with|pe|in aplicatia|cu)\\s+(?:nuvio(?:\\s+arc)?|the tv|tv)$");
    private static final Pattern LATEST = Pattern.compile(
            "^(?:the\\s+)?(?:latest|last|newest|most recent|new)\\s+episode\\s+(?:of|from|in)\\s+(.+)$"
                    + "|^ultimul\\s+episod\\s+(?:din|de la|al|de)\\s+(.+)$"
                    + "|^(.+?)\\s+(?:latest|last|newest)\\s+episode$");
    private static final Pattern SEASON_EPISODE_OF = Pattern.compile(
            "^(?:season|sezonul)\\s+(\\d+)\\s*,?\\s*(?:episode|episodul)\\s+(\\d+)\\s+(?:of|from|in|din|de la|al)\\s+(.+)$");
    private static final Pattern EPISODE_OF = Pattern.compile(
            "^(?:episode|episodul)\\s+(\\d+)(?:\\s*,?\\s*(?:of\\s+)?(?:season|sezonul|din sezonul)\\s+(\\d+))?\\s+(?:of|from|in|din|de la|al)\\s+(.+)$");
    private static final Pattern TITLE_SEASON_EPISODE = Pattern.compile(
            "^(.+?)\\s+(?:season|sezonul)\\s+(\\d+)\\s*,?\\s*(?:episode|episodul)\\s+(\\d+)$"
                    + "|^(.+?)\\s+s\\s?(\\d+)\\s*e\\s?(\\d+)$");
    private static final Pattern TITLE_EPISODE = Pattern.compile("^(.+?)\\s+(?:episode|episodul)\\s+(\\d+)$");

    private VoicePlay() {
    }

    /** The request in {@code spoken}, or null when it is not a play request (Assistant keeps those). */
    static Request parse(String spoken) {
        if (spoken == null) return null;
        String s = normalize(spoken);
        Matcher v = VERB.matcher(s);
        if (!v.matches()) return null;
        String rest = TRAILING_APP.matcher(v.group(1).trim()).replaceFirst("").trim();
        Request r = new Request();
        Matcher m;
        if ((m = LATEST.matcher(rest)).matches()) {
            r.latest = true;
            r.title = firstGroup(m, 1, 2, 3);
        } else if ((m = SEASON_EPISODE_OF.matcher(rest)).matches()) {
            r.season = Integer.parseInt(m.group(1));
            r.episode = Integer.parseInt(m.group(2));
            r.title = m.group(3);
        } else if ((m = EPISODE_OF.matcher(rest)).matches()) {
            r.episode = Integer.parseInt(m.group(1));
            if (m.group(2) != null) r.season = Integer.parseInt(m.group(2));
            r.title = m.group(3);
        } else if ((m = TITLE_SEASON_EPISODE.matcher(rest)).matches()) {
            boolean words = m.group(1) != null;
            r.title = words ? m.group(1) : m.group(4);
            r.season = Integer.parseInt(words ? m.group(2) : m.group(5));
            r.episode = Integer.parseInt(words ? m.group(3) : m.group(6));
        } else if ((m = TITLE_EPISODE.matcher(rest)).matches()) {
            r.title = m.group(1);
            r.episode = Integer.parseInt(m.group(2));
        } else {
            r.title = rest;
        }
        r.title = r.title.replaceFirst("^(?:the movie|the show|the series|filmul|serialul)\\s+", "").trim();
        return r.title.isEmpty() ? null : r;
    }

    /** Lower case, no accents, digits for spoken numbers up to twenty. */
    static String normalize(String text) {
        String s = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[\"“”„.!?]", " ")
                .replaceAll("\\s+", " ").trim();
        String[][] numbers = {
                {"one", "1"}, {"two", "2"}, {"three", "3"}, {"four", "4"}, {"five", "5"}, {"six", "6"},
                {"seven", "7"}, {"eight", "8"}, {"nine", "9"}, {"ten", "10"}, {"eleven", "11"}, {"twelve", "12"},
                {"thirteen", "13"}, {"fourteen", "14"}, {"fifteen", "15"}, {"sixteen", "16"}, {"seventeen", "17"},
                {"eighteen", "18"}, {"nineteen", "19"}, {"twenty", "20"},
                {"unu", "1"}, {"doi", "2"}, {"trei", "3"}, {"patru", "4"}, {"cinci", "5"}, {"sase", "6"},
                {"sapte", "7"}, {"opt", "8"}, {"noua", "9"}, {"zece", "10"}};
        for (String[] n : numbers) {
            s = s.replaceAll("\\b(episode|episodul|season|sezonul) " + n[0] + "\\b", "$1 " + n[1]);
        }
        return s;
    }

    private static String firstGroup(Matcher m, int... groups) {
        for (int g : groups) if (m.group(g) != null) return m.group(g).trim();
        return "";
    }

    /**
     * Starts {@code spoken} in the content app when it is a play request; returns false right away otherwise.
     * Resolving runs off the main thread; a toast says what is starting, or that nothing was found.
     */
    static boolean handle(Context context, String spoken) {
        Request r = parse(spoken);
        if (r == null) return false;
        Context c = context.getApplicationContext();
        android.widget.Toast.makeText(c, c.getString(R.string.voice_looking, r.title), android.widget.Toast.LENGTH_SHORT).show();
        Net.API.execute(() -> {
            Intent play = null;
            try {
                List<Catalog.Item> cw = Trakt.lastContinueWatching;
                if (cw.isEmpty() && Trakt.configured() && Trakt.signedIn(c)) {
                    // Voice can arrive before Home ever loaded Continue Watching in this process.
                    try {
                        cw = Trakt.continueWatching(c, Player.pkg(c));
                    } catch (Exception e) {
                        Log.w(TAG, "continue watching for voice failed", e);
                    }
                    if (cw == null) cw = new ArrayList<>();
                }
                play = resolve(c, r, cw);
            } catch (Exception e) {
                Log.w(TAG, "resolve failed for " + r, e);
            }
            Intent result = play;
            Net.MAIN.post(() -> {
                if (result == null) {
                    android.widget.Toast.makeText(c, c.getString(R.string.voice_not_found, r.title), android.widget.Toast.LENGTH_LONG).show();
                    return;
                }
                try {
                    c.startActivity(result);
                } catch (RuntimeException e) {
                    android.widget.Toast.makeText(c, R.string.not_available, android.widget.Toast.LENGTH_SHORT).show();
                }
            });
        });
        return true;
    }

    // ---- resolving ----

    /** Finds the title and episode and returns the intent that starts it in the content app, or null. */
    static Intent resolve(Context c, Request r, List<Catalog.Item> continueWatching) throws Exception {
        String addon = c.getString(R.string.addon_url);
        String pkg = Player.pkg(c);
        boolean wantsEpisode = r.latest || r.episode > 0 || r.season > 0;

        Catalog.Item best = null;
        int bestScore = -1;
        for (String type : wantsEpisode ? new String[]{"series"} : new String[]{"movie", "series"}) {
            List<Catalog.Item> found;
            try {
                found = Catalog.search(addon, type, r.title);
            } catch (Exception e) {
                continue;
            }
            for (int i = 0; i < found.size() && i < 5; i++) {
                Catalog.Item it = found.get(i);
                int score = score(it.name, r.title) * 10 - i;
                // A show you are in the middle of wins a tie: "play from" means the series you are watching.
                if (inProgress(continueWatching, it.id)) score += 5;
                if (score > bestScore) {
                    bestScore = score;
                    best = it;
                }
            }
        }
        if (best == null) return null;
        Log.i(TAG, "request " + r + " -> " + best.type + " " + best.id + " '" + best.name + "'");

        if (!"series".equals(best.type)) return playIntent(pkg, best, -1, -1, null);

        // Series: pick the episode from the addon's episode list.
        String url = addon + "/meta/series/" + Uri.encode(best.id) + ".json";
        JSONObject meta = new JSONObject(new String(Net.get(url), StandardCharsets.UTF_8)).optJSONObject("meta");
        JSONArray videos = meta != null ? meta.optJSONArray("videos") : null;
        List<int[]> aired = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        long now = System.currentTimeMillis();
        if (videos != null) {
            for (int i = 0; i < videos.length(); i++) {
                JSONObject v = videos.getJSONObject(i);
                int s = v.optInt("season"), e = v.optInt("episode", v.optInt("number"));
                if (s <= 0 || e <= 0) continue;
                long released = time(v.optString("released"));
                if (released > now) continue;
                aired.add(new int[]{s, e});
                titles.add(v.optString("name", v.optString("title", "")));
            }
        }
        aired.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));

        int season = -1, episode = -1;
        Catalog.Item current = find(continueWatching, best.id);
        if (r.latest && current != null && current.season > 0) {
            // "Latest episode" means where you are: the paused or next episode from Continue Watching.
            season = current.season;
            episode = current.episode;
        } else if (r.latest) {
            if (!aired.isEmpty()) {
                int[] last = aired.get(aired.size() - 1);
                season = last[0];
                episode = last[1];
            }
        } else if (r.episode > 0) {
            episode = r.episode;
            if (r.season > 0) {
                season = r.season;
            } else if (current != null && current.season > 0 && has(aired, current.season, r.episode)) {
                season = current.season;
            } else {
                // The latest season that has that episode, else count episodes across seasons.
                for (int[] a : aired) if (a[1] == r.episode) season = a[0];
                if (season < 0 && r.episode <= aired.size()) {
                    int[] abs = aired.get(r.episode - 1);
                    season = abs[0];
                    episode = abs[1];
                }
            }
        } else if (r.season > 0) {
            season = r.season;
            episode = 1;
        } else if (current != null && current.season > 0) {
            season = current.season;
            episode = current.episode;
        } else if (!aired.isEmpty()) {
            season = aired.get(0)[0];
            episode = aired.get(0)[1];
        }
        if (season < 0 || episode < 0) return null;
        String episodeTitle = null;
        for (int i = 0; i < aired.size(); i++) {
            if (aired.get(i)[0] == season && aired.get(i)[1] == episode) episodeTitle = titles.get(i);
        }
        return playIntent(pkg, best, season, episode, episodeTitle);
    }

    /** The extras Nuvio reads on its own home-screen programs: it opens the stream picker for that video. */
    static Intent playIntent(String pkg, Catalog.Item it, int season, int episode, String episodeTitle) {
        if (!Player.isNuvio(pkg)) return Player.stremioPlay(pkg, it, season, episode);
        boolean series = season > 0 && episode > 0;
        String logo = it.logo != null ? it.logo : "https://images.metahub.space/logo/medium/" + it.id + "/img";
        Intent i = new Intent(Intent.ACTION_VIEW)
                .setClassName(pkg, "com.nuvio.tv.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("contentId", it.id)
                .putExtra("contentType", series ? "series" : it.type)
                .putExtra("videoId", series ? it.id + ":" + season + ":" + episode : it.id)
                .putExtra("name", it.name)
                .putExtra("poster", it.poster)
                .putExtra("backdrop", it.background)
                .putExtra("logo", logo)
                .putExtra("launchMode", "stream");
        if (series) {
            i.putExtra("season", season).putExtra("episode", episode);
            if (episodeTitle != null && !episodeTitle.isEmpty()) i.putExtra("episodeTitle", episodeTitle);
        }
        return i;
    }

    private static int score(String name, String wanted) {
        String a = normalize(name).replaceAll("[^a-z0-9 ]", "").trim();
        String b = wanted.replaceAll("[^a-z0-9 ]", "").trim();
        String a2 = a.replaceFirst("^the ", ""), b2 = b.replaceFirst("^the ", "");
        if (a.equals(b) || a2.equals(b2)) return 3;
        if (a2.startsWith(b2) || b2.startsWith(a2)) return 2;
        if (a2.contains(b2) || b2.contains(a2)) return 1;
        return 0;
    }

    private static boolean inProgress(List<Catalog.Item> cw, String id) {
        return find(cw, id) != null;
    }

    private static Catalog.Item find(List<Catalog.Item> cw, String id) {
        if (cw == null || id == null) return null;
        for (Catalog.Item it : cw) if (id.equals(it.id)) return it;
        return null;
    }

    private static boolean has(List<int[]> aired, int season, int episode) {
        for (int[] a : aired) if (a[0] == season && a[1] == episode) return true;
        return false;
    }

    private static long time(String iso) {
        if (iso == null || iso.length() < 10) return 0;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(iso.substring(0, 10)).getTime();
        } catch (ParseException e) {
            return 0;
        }
    }
}
