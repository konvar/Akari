package com.konvar.tvhome;

import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Plain HttpURLConnection on three worker threads: no networking library, so nothing extra in memory. */
final class Net {

    static final ExecutorService IO = Executors.newFixedThreadPool(3);
    /**
     * Artwork downloads, newest request first: after a fast scroll the posters now on screen load before the
     * hundreds that already scrolled away (those are skipped when their turn comes).
     */
    static final ExecutorService IMAGES = new java.util.concurrent.ThreadPoolExecutor(4, 4, 30,
            java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingDeque<Runnable>() {
                @Override
                public boolean offer(Runnable r) {
                    return offerFirst(r);
                }
            });
    /** Account calls (Trakt, channels) on their own thread, so they never wait behind artwork downloads. */
    static final ExecutorService API = Executors.newSingleThreadExecutor();
    static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Net() {
    }

    static final class Response {
        final int code;
        final String body;
        String pageCount;
        String retryAfter;
        String itemCount;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    /** JSON request with extra headers; never throws on HTTP error codes, the caller reads {@code code}. */
    /** Like jsonOnce, but waits and retries when the server says too many requests (429), as Trakt does on bursts. */
    static Response json(String method, String url, java.util.Map<String, String> headers, String body)
            throws IOException {
        for (int attempt = 0; ; attempt++) {
            Response r = jsonOnce(method, url, headers, body);
            if (r.code != 429 || attempt >= 5) return r;
            long wait = 1000L * (attempt + 1);
            try {
                if (r.retryAfter != null) wait = Math.max(wait, (long) (Double.parseDouble(r.retryAfter.trim()) * 1000));
            } catch (NumberFormatException ignored) {
            }
            try {
                Thread.sleep(Math.min(wait, 15000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return r;
            }
        }
    }

    private static Response jsonOnce(String method, String url, java.util.Map<String, String> headers, String body)
            throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestMethod(method);
        c.setUseCaches(false);
        if (!headers.containsKey("Content-Type")) c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("User-Agent", "tvhome/3");
        for (java.util.Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
        try {
            if (body != null) {
                c.setDoOutput(true);
                try (java.io.OutputStream out = c.getOutputStream()) {
                    out.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = "";
            if (in != null) {
                try (InputStream s = in) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream(8 * 1024);
                    byte[] buf = new byte[8 * 1024];
                    int n;
                    while ((n = s.read(buf)) > 0) out.write(buf, 0, n);
                    text = out.toString("UTF-8");
                }
            }
            Response res = new Response(code, text);
            res.pageCount = c.getHeaderField("X-Pagination-Page-Count");
            res.itemCount = c.getHeaderField("X-Pagination-Item-Count");
            res.retryAfter = c.getHeaderField("Retry-After");
            return res;
        } finally {
            c.disconnect();
        }
    }

    static byte[] get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        c.setRequestProperty("User-Agent", "tvhome/3");
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(32 * 1024);
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }
}
