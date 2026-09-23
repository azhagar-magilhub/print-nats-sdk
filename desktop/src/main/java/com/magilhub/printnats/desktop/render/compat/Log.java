package com.magilhub.printnats.desktop.render.compat;

/**
 * android.util.Log. The legacy code logs very chattily at ERROR level; by default everything is dropped. Install a
 * {@link Sink} (e.g. forwarding to the sidecar's LogSink) to see it, or set {@code -Dprintnats.legacyLog=true} for
 * stderr.
 */
public final class Log {
    public static final int VERBOSE = 2;
    public static final int DEBUG = 3;
    public static final int INFO = 4;
    public static final int WARN = 5;
    public static final int ERROR = 6;

    public interface Sink {
        void log(int priority, String tag, String msg, Throwable tr);
    }

    private static volatile Sink sink = Boolean.getBoolean("printnats.legacyLog")
            ? new Sink() {
                @Override
                public void log(int priority, String tag, String msg, Throwable tr) {
                    System.err.println("[legacy " + priority + "] " + tag + ": " + msg);
                    if (tr != null) tr.printStackTrace();
                }
            }
            : null;

    private Log() {
    }

    public static void setSink(Sink s) {
        sink = s;
    }

    private static int emit(int p, String tag, String msg, Throwable tr) {
        Sink s = sink;
        if (s != null) s.log(p, tag, msg, tr);
        return 0;
    }

    public static int v(String tag, String msg) { return emit(VERBOSE, tag, msg, null); }
    public static int d(String tag, String msg) { return emit(DEBUG, tag, msg, null); }
    public static int i(String tag, String msg) { return emit(INFO, tag, msg, null); }
    public static int w(String tag, String msg) { return emit(WARN, tag, msg, null); }
    public static int e(String tag, String msg) { return emit(ERROR, tag, msg, null); }
    public static int v(String tag, String msg, Throwable tr) { return emit(VERBOSE, tag, msg, tr); }
    public static int d(String tag, String msg, Throwable tr) { return emit(DEBUG, tag, msg, tr); }
    public static int i(String tag, String msg, Throwable tr) { return emit(INFO, tag, msg, tr); }
    public static int w(String tag, String msg, Throwable tr) { return emit(WARN, tag, msg, tr); }
    public static int e(String tag, String msg, Throwable tr) { return emit(ERROR, tag, msg, tr); }
    public static int w(String tag, Throwable tr) { return emit(WARN, tag, "", tr); }
}
