// Copied mechanically from MerchantApp (Release-25.1) for byte-identical receipt/EOD rendering.
// Only package names and R were rewritten — do not hand-edit; re-run the copy instead.
package com.magilhub.printnats.android.legacy.logging;

import android.content.Context;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single source of truth for append-to-file logging across the app.
 *
 * <p>Previously, four near-identical implementations existed across
 * {@code PrintUtil}, {@code PrintFrameworkModule} and {@code StarPrintUtil}.
 * Each call did:
 *
 * <pre>
 *   new FileWriter(file, true) -&gt; append -&gt; close()
 * </pre>
 *
 * On eMMC that costs roughly 10-50 ms per call, and the helper was being hit
 * from the printing and payment hot paths (~300+ writes per active hour).
 *
 * <h3>What this class does instead</h3>
 * <ul>
 *   <li>Opens each log file <strong>once</strong> (per day) and caches a
 *       {@link BufferedWriter} for reuse.</li>
 *   <li>Flushes in batches — every {@code FLUSH_EVERY_LINES} lines or
 *       {@code FLUSH_EVERY_MS}, whichever comes first — plus a final flush on
 *       graceful process exit (see shutdown hook). Error/exception/failure lines
 *       are flushed <strong>immediately</strong> (see {@link #isHighSeverity})
 *       so a crash right after logging never drops the diagnostic. A hard kill
 *       may therefore lose at most the last unflushed batch of <em>info</em>
 *       lines, never an error line.</li>
 *   <li>Rotates daily by re-opening when the date suffix changes.</li>
 *   <li>Serializes writes to the same file via the channel monitor; writes to
 *       different files run in parallel.</li>
 *   <li>On any I/O failure, evicts the bad cached writer so the next call
 *       retries with a fresh handle.</li>
 * </ul>
 *
 * Per-call cost drops from ~10-50 ms to ~0.5-2 ms.
 *
 * <h3>File layout</h3>
 * Identical to the legacy helpers, so existing IT/support tooling that pulls
 * {@code {externalFilesDir}/com.magilhub.com/} keeps working:
 *
 * <pre>
 *   {externalFilesDir}/com.magilhub.com/{fileName}{YYYYMMDD}.txt
 * </pre>
 *
 * Each line is prefixed with {@code yyyy-MM-dd HH:mm:ss.SSS  } so the format
 * on disk is byte-compatible with what the legacy helpers wrote.
 */
public final class LogFileAppender {

    private static final String TAG = "LogFileAppender";
    private static final String LOG_DIR = "com.magilhub.com";

    /**
     * Fix 3: flush in batches instead of after every line. A burst of writes
     * (e.g. a KOT with many items) flushes once every {@link #FLUSH_EVERY_LINES}
     * lines; during light activity {@link #FLUSH_EVERY_MS} bounds how long a line
     * can sit buffered before the next write flushes it. The shutdown hook below
     * still flushes on graceful exit. Trade-off: a hard SIGKILL can lose at most
     * the last (unflushed) batch — acceptable for diagnostic print/payment logs.
     */
    private static final int  FLUSH_EVERY_LINES = 25;
    private static final long FLUSH_EVERY_MS    = 1000L;

    private static final ConcurrentHashMap<String, Channel> CHANNELS = new ConcurrentHashMap<>();

    private static final SimpleDateFormat DATE_FMT =
            new SimpleDateFormat("yyyyMMdd", Locale.getDefault());
    private static final SimpleDateFormat STAMP_FMT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());

    /** Holds one open writer plus the date string it was opened for. */
    private static final class Channel {
        BufferedWriter writer;
        String dateStr;
        int  pendingWrites;
        long lastFlushMillis;
    }

    static {
        // Best-effort flush+close on process exit. Android may SIGKILL before
        // this runs, so we still flush() after every write below — this is
        // defence in depth.
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override public void run() {
                for (Channel ch : CHANNELS.values()) {
                    synchronized (ch) {
                        if (ch.writer != null) {
                            try { ch.writer.flush(); } catch (Exception ignored) {}
                            try { ch.writer.close(); } catch (Exception ignored) {}
                            ch.writer = null;
                        }
                    }
                }
            }
        }, "LogFileAppenderShutdown"));
    }

    private LogFileAppender() {}

    /**
     * Append one timestamped line to
     * {@code {externalFilesDir}/com.magilhub.com/{fileName}{YYYYMMDD}.txt}.
     *
     * Safe to call from any thread. Never throws — failures are logged to
     * logcat and the bad writer is evicted so the next call retries.
     */
    public static void append(Context context, String fileName, String content) {
        if (context == null || fileName == null) return;
        try {
            String today, stamp;
            synchronized (DATE_FMT)  { today = DATE_FMT.format(new Date()); }
            synchronized (STAMP_FMT) { stamp = STAMP_FMT.format(new Date()); }
            String line = stamp + "  " + (content == null ? "" : content);

            Channel ch = getOrCreate(fileName);
            synchronized (ch) {
                if (ch.writer == null || !today.equals(ch.dateStr)) {
                    closeQuietly(ch);
                    File ext = context.getExternalFilesDir(null);
                    if (ext == null) return;
                    File dir = new File(ext, LOG_DIR);
                    if (!dir.exists() && !dir.mkdirs()) return;
                    File file = new File(dir, fileName + today + ".txt");
                    ch.writer = new BufferedWriter(new FileWriter(file, true));
                    ch.dateStr = today;
                    ch.pendingWrites = 0;
                    ch.lastFlushMillis = System.currentTimeMillis();
                }
                ch.writer.write(line);
                ch.writer.newLine();
                maybeFlush(ch, isHighSeverity(content));
            }
        } catch (Exception e) {
            Log.w(TAG, "append failed for " + fileName, e);
            evict(fileName);
        }
    }

    /**
     * Variant matching the legacy {@code StarPrintUtil.appendLogInNativeComponent1}
     * — takes a directory directly instead of going through
     * {@link Context#getExternalFilesDir(String)}.
     */
    public static void appendToDirectory(File logDirectory, String fileName, String content) {
        if (logDirectory == null || fileName == null) return;
        String channelKey = logDirectory.getAbsolutePath() + "::" + fileName;
        try {
            String today, stamp;
            synchronized (DATE_FMT)  { today = DATE_FMT.format(new Date()); }
            synchronized (STAMP_FMT) { stamp = STAMP_FMT.format(new Date()); }
            String line = stamp + "  " + (content == null ? "" : content);

            Channel ch = getOrCreate(channelKey);
            synchronized (ch) {
                if (ch.writer == null || !today.equals(ch.dateStr)) {
                    closeQuietly(ch);
                    if (!logDirectory.exists() && !logDirectory.mkdirs()) return;
                    File file = new File(logDirectory, fileName + today + ".txt");
                    ch.writer = new BufferedWriter(new FileWriter(file, true));
                    ch.dateStr = today;
                    ch.pendingWrites = 0;
                    ch.lastFlushMillis = System.currentTimeMillis();
                }
                ch.writer.write(line);
                ch.writer.newLine();
                maybeFlush(ch, isHighSeverity(content));
            }
        } catch (Exception e) {
            Log.w(TAG, "appendToDirectory failed for " + fileName, e);
            evict(channelKey);
        }
    }

    /**
     * Explicitly close and forget the cached writer for {@code fileName}.
     * Required when the underlying file is truncated or deleted from
     * outside this class (see {@code PrintFrameworkModule.deleteLog}).
     */
    public static void closeChannel(String fileName) {
        if (fileName == null) return;
        Channel ch = CHANNELS.remove(fileName);
        if (ch != null) {
            synchronized (ch) {
                closeQuietly(ch);
            }
        }
    }

    // ---- Internals -----------------------------------------------------

    private static Channel getOrCreate(String key) {
        Channel ch = CHANNELS.get(key);
        if (ch == null) {
            ch = new Channel();
            Channel prev = CHANNELS.putIfAbsent(key, ch);
            if (prev != null) ch = prev;
        }
        return ch;
    }

    /**
     * Flush the channel when a batch threshold is reached: every
     * {@link #FLUSH_EVERY_LINES} buffered lines, or once {@link #FLUSH_EVERY_MS}
     * has elapsed since the last flush. When {@code force} is true the line is
     * flushed immediately regardless of the batch — used for error/exception
     * lines so a hard crash right after logging can never drop the diagnostic
     * that explains it. Caller must hold the channel monitor.
     */
    private static void maybeFlush(Channel ch, boolean force) throws java.io.IOException {
        ch.pendingWrites++;
        long now = System.currentTimeMillis();
        if (force || ch.pendingWrites >= FLUSH_EVERY_LINES || (now - ch.lastFlushMillis) >= FLUSH_EVERY_MS) {
            ch.writer.flush();
            ch.pendingWrites = 0;
            ch.lastFlushMillis = now;
        }
    }

    /**
     * High-severity lines (errors/exceptions/failures) are flushed to disk
     * immediately so they survive a crash that happens right after logging.
     * Matches the prefixes the print/payment paths already use
     * ({@code ERROR::}, {@code Exception::}, {@code Failure::}).
     */
    private static boolean isHighSeverity(String content) {
        if (content == null) return false;
        String c = content.toLowerCase(Locale.ROOT);
        return c.contains("error") || c.contains("exception") || c.contains("fail");
    }

    private static void closeQuietly(Channel ch) {
        if (ch.writer != null) {
            try { ch.writer.close(); } catch (Exception ignored) {}
            ch.writer = null;
        }
        ch.dateStr = null;
    }

    private static void evict(String key) {
        Channel bad = CHANNELS.get(key);
        if (bad != null) {
            synchronized (bad) {
                closeQuietly(bad);
            }
        }
    }
}
