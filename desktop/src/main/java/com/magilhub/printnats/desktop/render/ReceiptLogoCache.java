package com.magilhub.printnats.desktop.render;

import com.magilhub.printnats.desktop.render.compat.Bitmap;
import com.magilhub.printnats.desktop.render.compat.BitmapFactory;
import com.magilhub.printnats.desktop.render.compat.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * Desktop twin of the Android adapter's {@code com.magilhub.printnats.android.render.ReceiptLogoCache}.
 *
 * The copied legacy renderer calls {@code ReceiptLogoCache.load(context, url)} for the receipt logo, and
 * {@code legacy/copy-from-android.sh} rewrites that reference onto this class (the whole
 * {@code …android.render} package maps to {@code …desktop.render}). Without it the generated copy does not
 * compile, because {@code …android.render} is not on the desktop classpath.
 *
 * Same contract as the Android one: this machine's saved copy first so a receipt still prints its logo offline,
 * else download once and save. Null when neither works — the renderer then simply draws no logo. The receipt
 * payload only ever carries the logo URL; the image itself never travels over NATS.
 *
 * Only {@code load} is ported. Android also has {@code prefetch} (a background warm-up on configure /
 * restaurant update) and {@code urlFrom}; on desktop the first print does the fetch instead.
 */
public final class ReceiptLogoCache {
    private static final int TIMEOUT_MS = 8000;
    private static final Object LOCK = new Object();

    private ReceiptLogoCache() {
    }

    /** The logo bitmap: this machine's saved copy, else downloaded (and saved). Null if neither works. */
    public static Bitmap load(Context context, String url) {
        if (url == null || url.trim().isEmpty()) return null;
        File f = file(context, url);
        if (f.length() > 0) {
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (b != null) return b;
            //noinspection ResultOfMethodCallIgnored
            f.delete(); // unreadable — fetch again
        }
        try {
            byte[] bytes = download(context, url);
            return bytes == null ? null : BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Throwable e) {
            return null;
        }
    }

    private static byte[] download(Context context, String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        try {
            if (c.getResponseCode() / 100 != 2) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            in.close();
            byte[] bytes = out.toByteArray();
            // Only keep what actually decodes as an image.
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, probe);
            if (probe.outWidth <= 0 || probe.outHeight <= 0) return null;
            save(context, url, bytes);
            return bytes;
        } finally {
            c.disconnect();
        }
    }

    private static void save(Context context, String url, byte[] bytes) {
        synchronized (LOCK) {
            File target = file(context, url);
            File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
            FileOutputStream out = null;
            try {
                out = new FileOutputStream(tmp);
                out.write(bytes);
                out.close();
                out = null;
                //noinspection ResultOfMethodCallIgnored
                target.delete();
                if (!tmp.renameTo(target)) {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }
            } catch (Throwable ignored) {
                // A logo that cannot be cached is not worth failing a print over.
            } finally {
                try {
                    if (out != null) out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * Keyed by URL, like the Android cache, so a machine pointed at another location fetches that location's
     * logo. {@code getFilesDir()} is null on a Context built without one (previews, tests) — fall back to the
     * system temp dir rather than refusing to cache.
     */
    private static File file(Context context, String url) {
        File base = context == null ? null : context.getFilesDir();
        if (base == null) base = new File(System.getProperty("java.io.tmpdir", "."));
        File dir = new File(base, "printnats");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, "receipt_logo_" + sha1(url) + ".img");
    }

    private static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
