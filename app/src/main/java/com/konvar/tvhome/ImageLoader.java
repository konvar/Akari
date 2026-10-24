package com.konvar.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.IOException;

/**
 * Loads web images at the size they are shown, in RGB_565, into a small capped cache. A poster tile costs
 * about 90 KB this way instead of several MB for a full-size ARGB decode, which is most of the RAM saving.
 * Encoded bytes are cached on disk by the HttpResponseCache installed in MainActivity, so trimming the
 * memory cache when Home goes to the background only costs a disk read on return.
 */
final class ImageLoader {

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>((int) Math.min(48L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 6)) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getAllocationByteCount();
        }
    };

    /** Pictures the server does not have (HTTP 404), and when that was found; tried again after six hours. */
    private static final java.util.Map<String, Long> FAILED = new java.util.concurrent.ConcurrentHashMap<>();

    private ImageLoader() {
    }

    static void load(ImageView view, String url, int width, int height) {
        load(view, url, width, height, false, null);
    }

    /**
     * Shows {@code url} in {@code view}; {@code done} runs on the main thread once a bitmap is set. Pass
     * {@code alpha} for transparent artwork such as clear logos, which RGB_565 would put on black.
     */
    static void load(ImageView view, String url, int width, int height, boolean alpha, Runnable done) {
        view.setTag(R.id.image_url, url);
        if (url == null || url.isEmpty()) {
            view.setImageDrawable(null);
            if (done != null) done.run();
            return;
        }
        String key = url + '@' + width + (alpha ? "a" : "");
        Bitmap hit = CACHE.get(key);
        if (hit != null) {
            view.setImageBitmap(hit);
            if (done != null) done.run();
            return;
        }
        // A picture that does not exist (an episode still not made yet): go straight to the fallback instead of
        // blanking the card and asking again on every redraw, which made it flicker.
        Long failedAt = FAILED.get(url);
        if (failedAt != null && android.os.SystemClock.elapsedRealtime() - failedAt < 6 * 3600 * 1000L) {
            view.setImageDrawable(null);
            if (done != null) done.run();
            return;
        }
        view.setImageDrawable(null);
        Net.IMAGES.execute(() -> {
            // Skip downloads for tiles that scrolled away before their turn came.
            if (!url.equals(view.getTag(R.id.image_url))) return;
            Bitmap bmp = null;
            try {
                bmp = decode(read(view.getContext(), url), width, height, alpha);
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                android.util.Log.w("TvHomeImages", "failed " + url + ": " + e);
                if (e instanceof java.io.FileNotFoundException) FAILED.put(url, android.os.SystemClock.elapsedRealtime());
            }
            Bitmap result = bmp;
            Net.MAIN.post(() -> {
                if (result == null) {
                    if (done != null && url.equals(view.getTag(R.id.image_url))) done.run();
                    return;
                }
                CACHE.put(key, result);
                if (url.equals(view.getTag(R.id.image_url))) {
                    view.setImageBitmap(result);
                    if (done != null) done.run();
                }
            });
        });
    }

    /** Decodes {@code url} into the memory cache ahead of time (no view), for cards next to the focused one. */
    static void preload(android.content.Context c, String url, int width, int height, boolean alpha) {
        if (url == null || url.isEmpty()) return;
        String key = url + '@' + width + (alpha ? "a" : "");
        if (CACHE.get(key) != null) return;
        Net.IMAGES.execute(() -> {
            if (CACHE.get(key) != null) return;
            try {
                Bitmap bmp = decode(read(c, url), width, height, alpha);
                if (bmp != null) Net.MAIN.post(() -> CACHE.put(key, bmp));
            } catch (IOException | RuntimeException | OutOfMemoryError ignored) {
            }
        });
    }

    /** Web images over HTTP; content:// and android.resource:// art (TV channel posters) through the resolver. */
    private static byte[] read(android.content.Context c, String url) throws IOException {
        if (url.startsWith("http")) {
            // Every web image is kept on disk after its first download; Home never waits on the network twice.
            java.io.File f = diskFile(c, url);
            if (f.exists()) {
                f.setLastModified(System.currentTimeMillis());
                return java.nio.file.Files.readAllBytes(f.toPath());
            }
            byte[] data = Net.get(url);
            try {
                java.io.File tmp = new java.io.File(f.getPath() + ".tmp");
                java.nio.file.Files.write(tmp.toPath(), data);
                if (tmp.renameTo(f) && ++writes % 50 == 0) trimDisk(f.getParentFile());
            } catch (IOException ignored) {
            }
            return data;
        }
        try (java.io.InputStream in = c.getContentResolver().openInputStream(android.net.Uri.parse(url))) {
            if (in == null) throw new IOException("no stream for " + url);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static final long DISK_LIMIT = 1024L * 1024 * 1024;
    private static int writes;

    private static java.io.File diskFile(android.content.Context c, String url) {
        java.io.File dir = new java.io.File(c.getFilesDir(), "images");
        if (!dir.exists()) dir.mkdirs();
        String name;
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-1")
                    .digest(url.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            name = sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            name = Integer.toHexString(url.hashCode());
        }
        return new java.io.File(dir, name);
    }

    /** Keeps the image folder under 1 GB by removing the least recently used files. */
    private static void trimDisk(java.io.File dir) {
        java.io.File[] files = dir.listFiles();
        if (files == null) return;
        long total = 0;
        for (java.io.File f : files) total += f.length();
        if (total <= DISK_LIMIT) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (java.io.File f : files) {
            if (total <= DISK_LIMIT * 9 / 10) break;
            total -= f.length();
            f.delete();
        }
    }

    static Bitmap decode(byte[] data, int width, int height, boolean alpha) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= width && o.outHeight / (sample * 2) >= height) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        o.inPreferredConfig = alpha ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (b != null && b.getWidth() > width * 1.25f) {
            int h = Math.max(1, Math.round(b.getHeight() * (width / (float) b.getWidth())));
            Bitmap scaled = Bitmap.createScaledBitmap(b, width, h, true);
            if (scaled != b) b.recycle();
            b = scaled;
        }
        return b;
    }

    /** A dark tint of the image's average colour, for the ambient background. */
    static int ambient(Bitmap b, int base) {
        Bitmap tiny = Bitmap.createScaledBitmap(b, 6, 4, true);
        long r = 0, g = 0, bl = 0;
        int n = 0;
        for (int y = 0; y < tiny.getHeight(); y++) {
            for (int x = 0; x < tiny.getWidth(); x++) {
                int c = tiny.getPixel(x, y);
                if ((c >>> 24) < 128) continue;
                r += (c >> 16) & 0xFF;
                g += (c >> 8) & 0xFF;
                bl += c & 0xFF;
                n++;
            }
        }
        if (tiny != b) tiny.recycle();
        if (n == 0) return base;
        float[] hsv = new float[3];
        android.graphics.Color.RGBToHSV((int) (r / n), (int) (g / n), (int) (bl / n), hsv);
        hsv[1] = Math.min(hsv[1] * 1.1f, 0.75f);
        hsv[2] = Math.min(hsv[2], 0.24f);
        return android.graphics.Color.HSVToColor(hsv);
    }

    static Bitmap cached(String key) {
        return CACHE.get(key);
    }

    static void put(String key, Bitmap bitmap) {
        CACHE.put(key, bitmap);
    }

    static void trim() {
        CACHE.evictAll();
    }
}
