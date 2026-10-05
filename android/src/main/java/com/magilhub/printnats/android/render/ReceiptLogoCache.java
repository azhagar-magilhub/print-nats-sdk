package com.magilhub.printnats.android.render;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * Receipt logo, cached on this device so it prints offline.
 *
 * MerchantApp's offline build downloads the logo to base64 and sends it inside every receipt payload. Here the logo
 * never travels over NATS: only the device that renders the receipt (the master) needs it, and every device keeps
 * its own copy — downloaded once while online (prefetch on configure / restaurant update) and read from disk for
 * every print after that. The receipt payload keeps carrying only the logo URL.
 *
 * The file is keyed by the URL, so a device moved to another location fetches that location's logo.
 */
public final class ReceiptLogoCache {
    private static final String BASE_URL = "https://static.magilhub.com/";
    private static final int TIMEOUT_MS = 8000;
    private static final Object LOCK = new Object();

    private ReceiptLogoCache() {
    }

    /** Logo URL from the restaurant details (media[] entityType LOGO), MerchantApp getImageURL('LOGO'); null if none. */
    public static String urlFrom(JsonObject restaurant) {
        if (restaurant == null) return null;
        JsonElement m = restaurant.get("media");
        if (m == null || !m.isJsonArray()) return null;
        JsonArray media = m.getAsJsonArray();
        for (JsonElement e : media) {
            if (e == null || !e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            if (!"LOGO".equals(str(o, "entityType"))) continue;
            String id = str(o, "id");
            String mime = str(o, "mimeType");
            if (id == null || mime == null) return null;
            String[] parts = mime.split("/");
            if (parts.length < 2 || parts[0].isEmpty() || parts[1].isEmpty()) return null;
            return BASE_URL + parts[0] + "/" + id + "." + parts[1];
        }
        return null;
    }

    /** Download the restaurant's logo in the background if this device doesn't have it yet. */
    public static void prefetch(Context context, JsonObject restaurant) {
        final String url = urlFrom(restaurant);
        if (context == null || url == null) return;
        final Context app = context.getApplicationContext();
        if (file(app, url).length() > 0) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    download(app, url);
                } catch (Throwable ignored) {
                    // offline / bad URL — retried on the next prefetch or print
                }
            }
        }, "receipt-logo-prefetch").start();
    }

    /** The logo bitmap: this device's saved copy, else downloaded (and saved). Null if neither works. */
    public static Bitmap load(Context context, String url) {
        if (context == null || url == null || url.trim().isEmpty()) return null;
        Context app = context.getApplicationContext();
        File f = file(app, url);
        if (f.length() > 0) {
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (b != null) return b;
            //noinspection ResultOfMethodCallIgnored
            f.delete(); // unreadable — fetch again
        }
        try {
            byte[] bytes = download(app, url);
            return bytes == null ? null : BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Throwable e) {
            return null;
        }
    }

    private static byte[] download(Context app, String url) throws Exception {
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
            save(app, url, bytes);
            return bytes;
        } finally {
            c.disconnect();
        }
    }

    private static void save(Context app, String url, byte[] bytes) {
        synchronized (LOCK) {
            File target = file(app, url);
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
            } catch (Exception ignored) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            } finally {
                if (out != null) {
                    try {
                        out.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    private static File file(Context app, String url) {
        File dir = new File(app.getFilesDir(), "printnats");
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

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        if (e == null || e.isJsonNull()) return null;
        String v = e.getAsString();
        return v == null || v.isEmpty() ? null : v;
    }
}
