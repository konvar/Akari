package com.konvar.tvhome;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

/**
 * A title's trailer full screen, with sound, from the same source as the trailer behind the hero: IMDb's own file
 * first (no ads), YouTube as a backup. OK pauses and resumes, Back closes it. Seeking is left out: IMDb's files
 * restart from the beginning when asked to jump.
 */
public class TrailerActivity extends Activity {

    private FrameLayout root;
    private VideoView video;
    private WebView web;
    private TextView note;

    /** Plays the trailer of {@code imdb}; {@code youtubeKey} (may be null) is the backup when IMDb has none. */
    static void open(Context c, String imdb, boolean movie, String youtubeKey, String title) {
        c.startActivity(new Intent(c, TrailerActivity.class).putExtra("imdb", imdb).putExtra("movie", movie)
                .putExtra("key", youtubeKey).putExtra("title", title));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        ProgressBar spinner = new ProgressBar(this);
        root.addView(spinner, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        note = new TextView(this);
        note.setTextColor(0xCCFFFFFF);
        note.setTextSize(16);
        note.setTypeface(getResources().getFont(R.font.outfit));
        int pad = Rows.px(this, 32);
        note.setPadding(pad, pad, pad, pad);
        note.setText(getIntent().getStringExtra("title"));
        root.addView(note, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));
        setContentView(root);

        String imdb = getIntent().getStringExtra("imdb");
        boolean movie = getIntent().getBooleanExtra("movie", true);
        String backup = getIntent().getStringExtra("key");
        Net.IO.execute(() -> {
            String[] found = imdb != null && imdb.startsWith("tt") ? HeroTrailer.resolve(imdb, movie) : new String[]{"", ""};
            String mp4 = found[0].isEmpty() ? null : found[0];
            String key = !found[1].isEmpty() ? found[1] : backup;
            if (mp4 == null && key == null && imdb != null) key = Tmdb.trailer(imdb, movie);
            String yt = key;
            Net.MAIN.post(() -> {
                if (isFinishing()) return;
                spinner.setVisibility(View.GONE);
                if (mp4 != null) playFile(mp4, yt);
                else if (yt != null) playYouTube(yt);
                else {
                    Toast.makeText(this, "No trailer found", Toast.LENGTH_SHORT).show();
                    finish();
                }
            });
        });
    }

    private void playFile(String url, String backup) {
        video = new VideoView(this);
        root.addView(video, 0, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        video.setVideoURI(android.net.Uri.parse(url));
        video.setOnPreparedListener(mp -> {
            fadeNote();
            video.start();
        });
        video.setOnCompletionListener(mp -> finish());
        video.setOnErrorListener((mp, what, extra) -> {
            // The IMDb link failed (expired or blocked): YouTube instead, if there is one.
            root.removeView(video);
            video = null;
            if (backup != null) playYouTube(backup);
            else finish();
            return true;
        });
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void playYouTube(String key) {
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        web.setFocusable(false);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void ended() {
                Net.MAIN.post(TrailerActivity.this::finish);
            }
        }, "akari");
        root.addView(web, 0, new FrameLayout.LayoutParams(-1, -1));
        String html = "<!doctype html><html><head><meta name=viewport content='width=device-width'>"
                + "<style>html,body{margin:0;background:#000;overflow:hidden}#p{position:absolute;inset:0;width:100vw;height:100vh}</style>"
                + "</head><body><div id=p></div><script src='https://www.youtube.com/iframe_api'></script><script>"
                + "var p;function onYouTubeIframeAPIReady(){p=new YT.Player('p',{videoId:'" + key + "',playerVars:{"
                + "autoplay:1,controls:0,disablekb:1,fs:0,iv_load_policy:3,modestbranding:1,rel:0,playsinline:1},"
                + "events:{onReady:function(e){e.target.unMute();e.target.setVolume(100);e.target.playVideo();},"
                + "onStateChange:function(e){if(e.data===0)akari.ended();}}});}"
                + "function toggle(){if(!p)return;p.getPlayerState()===1?p.pauseVideo():p.playVideo();}"
                + "</script></body></html>";
        web.loadDataWithBaseURL("https://www.youtube.com", html, "text/html", "utf-8", null);
        fadeNote();
    }

    private void fadeNote() {
        note.postDelayed(() -> note.setVisibility(View.GONE), 3000);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        boolean ok = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY
                || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE;
        // Left / Right and the seek buttons do nothing (a jump restarted IMDb's trailers).
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || keyCode == KeyEvent.KEYCODE_MEDIA_REWIND) return true;
        if (video != null) {
            if (ok) {
                if (video.isPlaying()) video.pause();
                else video.start();
                return true;
            }
        } else if (web != null) {
            if (ok) {
                web.evaluateJavascript("toggle()", null);
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Leaving (Home, another app): the trailer ends rather than playing on unseen.
        finish();
    }

    @Override
    protected void onDestroy() {
        if (video != null) video.stopPlayback();
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
        }
        super.onDestroy();
    }
}
