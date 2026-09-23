package com.magilhub.printnats.android;

import android.content.Context;
import android.util.Log;

import com.magilhub.printnats.spi.LogSink;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Writes {@code <externalFilesDir>/com.magilhub.com/<prefix><yyyyMMdd>.txt} with lines
 * {@code "yyyy-MM-dd HH:mm:ss.SSS  content"} — the same files and format MerchantApp's LogFileAppender produces,
 * so the existing remote log fetch (print_, fcm_, natsStatus_, …) keeps working. Flushes every line.
 */
public final class AndroidLogSink implements LogSink {
    private static final String TAG = "PrintNats";
    private static final String LOG_DIR = "com.magilhub.com";

    private final Context context;
    private final Map<String, BufferedWriter> writers = new HashMap<>();
    private final Map<String, String> writerDates = new HashMap<>();

    public AndroidLogSink(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public synchronized void append(String fileName, String content) {
        if (fileName == null) return;
        try {
            Date now = new Date();
            String today = new SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(now);
            String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(now);
            BufferedWriter w = writers.get(fileName);
            if (w == null || !today.equals(writerDates.get(fileName))) {
                if (w != null) close(w);
                File ext = context.getExternalFilesDir(null);
                if (ext == null) return;
                File dir = new File(ext, LOG_DIR);
                if (!dir.exists() && !dir.mkdirs()) return;
                w = new BufferedWriter(new FileWriter(new File(dir, fileName + today + ".txt"), true));
                writers.put(fileName, w);
                writerDates.put(fileName, today);
            }
            w.write(stamp + "  " + (content == null ? "" : content));
            w.newLine();
            w.flush();
        } catch (IOException e) {
            Log.w(TAG, "log append failed for " + fileName, e);
            BufferedWriter bad = writers.remove(fileName);
            if (bad != null) close(bad);
        }
    }

    private static void close(BufferedWriter w) {
        try {
            w.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }
}
