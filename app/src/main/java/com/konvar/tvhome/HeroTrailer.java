package com.konvar.tvhome;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * A muted trailer playing behind the hero, over the backdrop and under its fades (opt-in, Settings → Home rows).
 * IMDb's own trailer file comes first: a plain MP4 played by Android's MediaPlayer, so no ads and no extra library.
 * When IMDb has none, YouTube's embedded player plays it, kept invisible until the trailer itself is playing, so an
 * ad before it would never show. Any failure leaves the backdrop as it is.
 */
final class HeroTrailer {


    private final FrameLayout box;
    private final Context c;
    private TextureView texture;
    private MediaPlayer player;
    private WebView web;
    private Catalog.Item current;
    private int generation;
    /** When the delay ends; the trailer may be ready (buffered) before that, and waits. */
    private long dueAt;
    private boolean prepared;
    private final Runnable start = () -> {
        if (prepared && player != null) {
            player.start();
        }
    };

    HeroTrailer(FrameLayout heroBox) {
        this.box = heroBox;
        this.c = heroBox.getContext();
    }

    /** Resolved trailers per title: [IMDb MP4 or "", YouTube key or ""], and when (IMDb's links expire). */
    private static final Map<String, String[]> FOUND = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, Long> FOUND_AT = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ExecutorService LOOKUP = java.util.concurrent.Executors.newFixedThreadPool(2);

    /** The trailer of a title (looked up once, kept 30 minutes): {mp4, youtubeKey}, either may be "". */
    static String[] resolve(String imdb, boolean movie) {
        Long at = FOUND_AT.get(imdb);
        String[] known = FOUND.get(imdb);
        if (known != null && at != null && System.currentTimeMillis() - at < 30 * 60 * 1000L) return known;
        String mp4 = imdbTrailer(imdb);
        String key = mp4 == null ? Tmdb.trailer(imdb, movie) : null;
        String[] r = {mp4 != null ? mp4 : "", key != null ? key : ""};
        FOUND.put(imdb, r);
        FOUND_AT.put(imdb, System.currentTimeMillis());
        return r;
    }

    /** Looks the trailer up ahead of time, for cards next to the focused one. */
    static void prefetch(Context c, Catalog.Item it) {
        if (!enabled(c) || it == null || it.id == null || !it.id.startsWith("tt")) return;
        if (!"movie".equals(it.type) && !"series".equals(it.type)) return;
        Long at = FOUND_AT.get(it.id);
        if (at != null && System.currentTimeMillis() - at < 30 * 60 * 1000L) return;
        LOOKUP.execute(() -> resolve(it.id, "movie".equals(it.type)));
    }

    static boolean enabled(Context c) {
        return c.getSharedPreferences("hero_trailer", Context.MODE_PRIVATE).getBoolean("on", false);
    }

    static void setEnabled(Context c, boolean on) {
        c.getSharedPreferences("hero_trailer", Context.MODE_PRIVATE).edit().putBoolean("on", on).apply();
    }

    /** Seconds a title stays focused before its trailer starts (Settings → Home rows). */
    static int delaySeconds(Context c) {
        return c.getSharedPreferences("hero_trailer", Context.MODE_PRIVATE).getInt("delay", 4);
    }

    static boolean sound(Context c) {
        return c.getSharedPreferences("hero_trailer", Context.MODE_PRIVATE).getBoolean("sound", false);
    }

    static void set(Context c, boolean on, boolean sound, int delaySeconds) {
        c.getSharedPreferences("hero_trailer", Context.MODE_PRIVATE).edit().putBoolean("on", on)
                .putBoolean("sound", sound).putInt("delay", delaySeconds).apply();
    }

    /** The hero shows this title now: stop what plays, and start its trailer after a few seconds of focus. */
    void focus(Catalog.Item it) {
        // The same title redrawn (ratings or age arriving): keep what is playing or about to.
        if (it != null && it == current) return;
        stop();
        current = it;
        if (it == null || it.id == null || !it.id.startsWith("tt") || !enabled(c) || !unmetered()) return;
        if (!"movie".equals(it.type) && !"series".equals(it.type)) return;
        dueAt = android.os.SystemClock.uptimeMillis() + delaySeconds(c) * 1000L;
        // Look up and buffer at once, invisibly; it starts and shows only when the delay is over.
        begin();
        box.postDelayed(start, delaySeconds(c) * 1000L);
    }

    /** Stops and hides the trailer; the backdrop shows again. */
    void stop() {
        generation++;
        box.removeCallbacks(start);
        if (texture != null) texture.setAlpha(0f);
        if (player != null) {
            try {
                player.release();
            } catch (RuntimeException ignored) {
            }
            player = null;
        }
        if (web != null) {
            web.setAlpha(0f);
            web.loadUrl("about:blank");
            box.removeView(web);
            web.destroy();
            web = null;
        }
    }

    private boolean unmetered() {
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkCapabilities nc = cm != null ? cm.getNetworkCapabilities(cm.getActiveNetwork()) : null;
        return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
    }

    private void begin() {
        Catalog.Item it = current;
        int gen = generation;
        prepared = false;
        LOOKUP.execute(() -> {
            String[] found = resolve(it.id, "movie".equals(it.type));
            String mp4 = found[0].isEmpty() ? null : found[0];
            String key = found[1].isEmpty() ? null : found[1];
            Net.MAIN.post(() -> {
                if (gen != generation) return;
                if (mp4 != null) playFile(mp4, gen);
                else if (key != null) playYouTube(key, gen);
            });
        });
    }

    // ---- IMDb: a plain MP4 ----

    /** The 720p (else smaller) file of the title's longest IMDb trailer; null when there is none. */
    static String imdbTrailer(String imdb) {
        try {
            Map<String, String> h = new HashMap<>();
            h.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130 Safari/537.36");
            h.put("x-imdb-client-name", "imdb-web-next");
            h.put("Origin", "https://www.imdb.com");
            String q = new JSONObject().put("query", "{title(id:\"" + imdb + "\"){primaryVideos(first:6){edges{node{"
                    + "contentType{id} runtime{value} playbackURLs{url videoMimeType videoDefinition}}}}}}").toString();
            Net.Response r = Net.json("POST", "https://api.graphql.imdb.com/", h, q);
            JSONObject t = new JSONObject(r.body).optJSONObject("data");
            t = t != null ? t.optJSONObject("title") : null;
            JSONObject pv = t != null ? t.optJSONObject("primaryVideos") : null;
            JSONArray edges = pv != null ? pv.optJSONArray("edges") : null;
            String best = null;
            int bestLength = -1;
            for (int i = 0; edges != null && i < edges.length(); i++) {
                JSONObject n = edges.getJSONObject(i).optJSONObject("node");
                if (n == null) continue;
                JSONObject type = n.optJSONObject("contentType");
                boolean trailer = type != null && "amzn1.imdb.video.contenttype.trailer".equals(type.optString("id"));
                JSONObject rt = n.optJSONObject("runtime");
                int length = rt != null ? rt.optInt("value") : 0;
                // Trailers first, longest first (the main trailer, not a teaser or a clip).
                int score = (trailer ? 100000 : 0) + length;
                String url = pick(n.optJSONArray("playbackURLs"));
                if (url != null && score > bestLength) {
                    bestLength = score;
                    best = url;
                }
            }
            return best;
        } catch (Exception e) {
            android.util.Log.w("TvHomeTrailer", "IMDb " + imdb + ": " + e);
            return null;
        }
    }

    private static String pick(JSONArray urls) {
        String fallback = null;
        for (int i = 0; urls != null && i < urls.length(); i++) {
            JSONObject u = urls.optJSONObject(i);
            if (u == null || !"MP4".equals(u.optString("videoMimeType"))) continue;
            if ("DEF_720p".equals(u.optString("videoDefinition"))) return u.optString("url");
            if (fallback == null || "DEF_480p".equals(u.optString("videoDefinition"))) fallback = u.optString("url");
        }
        return fallback;
    }

    private void playFile(String url, int gen) {
        if (texture == null) {
            texture = new TextureView(c);
            texture.setAlpha(0f);
            // Over the backdrop, under the fades into the page.
            box.addView(texture, 1, new FrameLayout.LayoutParams(-1, -1));
        }
        Runnable go = () -> {
            if (gen != generation) return;
            MediaPlayer mp = new MediaPlayer();
            player = mp;
            try {
                mp.setSurface(new Surface(texture.getSurfaceTexture()));
                mp.setDataSource(url);
                // Muted unless sound is switched on; then at a moderate level.
                float v = sound(c) ? 0.6f : 0f;
                mp.setVolume(v, v);
                mp.setOnVideoSizeChangedListener((p, w, h) -> crop(w, h));
                mp.setOnInfoListener((p, what, extra) -> {
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && gen == generation) texture.setAlpha(1f);
                    return false;
                });
                mp.setOnCompletionListener(p -> {
                    if (gen == generation) texture.setAlpha(0f);
                });
                mp.setOnErrorListener((p, what, extra) -> {
                    if (gen == generation) texture.setAlpha(0f);
                    return true;
                });
                mp.setOnPreparedListener(p -> {
                    if (gen != generation) return;
                    prepared = true;
                    // Buffered early: wait for the end of the delay, else start now.
                    if (android.os.SystemClock.uptimeMillis() >= dueAt) p.start();
                });
                mp.prepareAsync();
            } catch (Exception e) {
                android.util.Log.w("TvHomeTrailer", "play: " + e);
            }
        };
        if (texture.isAvailable()) {
            go.run();
        } else {
            texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) {
                    go.run();
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) {
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture s) {
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture s) {
                }
            });
        }
    }

    /** Fills the hero like the backdrop does (centre crop). */
    private void crop(int vw, int vh) {
        if (vw <= 0 || vh <= 0 || texture == null) return;
        float w = texture.getWidth(), h = texture.getHeight();
        float scale = Math.max(w / vw, h / vh);
        Matrix m = new Matrix();
        m.setScale(vw * scale / w, vh * scale / h, w / 2, h / 2);
        texture.setTransform(m);
    }

    // ---- YouTube backup: invisible until the trailer itself plays ----

    @SuppressLint("SetJavaScriptEnabled")
    private void playYouTube(String key, int gen) {
        web = new WebView(c);
        web.setAlpha(0f);
        web.setFocusable(false);
        web.setBackgroundColor(0);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void playing() {
                Net.MAIN.post(() -> {
                    if (gen != generation || web == null) return;
                    long wait = dueAt - android.os.SystemClock.uptimeMillis();
                    if (wait <= 0) web.setAlpha(1f);
                    else web.postDelayed(() -> {
                        if (gen == generation && web != null) web.setAlpha(1f);
                    }, wait);
                });
            }

            @JavascriptInterface
            public void ended() {
                Net.MAIN.post(() -> {
                    if (gen == generation && web != null) web.setAlpha(0f);
                });
            }
        }, "akari");
        box.addView(web, 1, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // The embed reports the trailer's own position: during an ad it stays at 0, so it is shown only once the
        // trailer is really moving.
        boolean withSound = sound(c);
        String html = "<!doctype html><html><head><meta name=viewport content='width=device-width'>"
                + "<style>html,body{margin:0;background:transparent;overflow:hidden}#p{position:absolute;top:50%;left:50%;"
                + "width:177.78vh;height:100vh;min-width:100vw;min-height:56.25vw;transform:translate(-50%,-50%)}</style>"
                + "</head><body><div id=p></div><script src='https://www.youtube.com/iframe_api'></script><script>"
                + "var p;function onYouTubeIframeAPIReady(){p=new YT.Player('p',{videoId:'" + key + "',playerVars:{"
                + "autoplay:1,mute:" + (withSound ? 0 : 1) + ",controls:0,disablekb:1,fs:0,iv_load_policy:3,modestbranding:1,rel:0,playsinline:1,"
                + "cc_load_policy:0},events:{onReady:function(e){" + (withSound ? "e.target.unMute();e.target.setVolume(60);" : "e.target.mute();")
                + "e.target.playVideo();},"
                + "onStateChange:function(e){if(e.data===0)akari.ended();}}});"
                + "setInterval(function(){try{if(p&&p.getPlayerState()===1&&p.getCurrentTime()>0.6)akari.playing();}catch(x){}},500);}"
                + "</script></body></html>";
        web.loadDataWithBaseURL("https://www.youtube.com", html, "text/html", "utf-8", null);
    }
}
