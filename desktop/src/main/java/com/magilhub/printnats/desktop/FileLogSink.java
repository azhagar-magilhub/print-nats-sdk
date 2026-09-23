package com.magilhub.printnats.desktop;

import com.magilhub.printnats.spi.LogSink;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Same files and line format as the Android/legacy logs: {@code <dir>/<prefix><yyyyMMdd>.txt}, "yyyy-MM-dd HH:mm:ss.SSS  line". */
public final class FileLogSink implements LogSink {
    private final File dir;
    private final Map<String, BufferedWriter> writers = new HashMap<>();
    private final Map<String, String> dates = new HashMap<>();

    public FileLogSink(File dir) {
        this.dir = dir;
        dir.mkdirs();
    }

    @Override
    public synchronized void append(String fileName, String content) {
        if (fileName == null) return;
        try {
            Date now = new Date();
            String today = new SimpleDateFormat("yyyyMMdd", Locale.US).format(now);
            BufferedWriter w = writers.get(fileName);
            if (w == null || !today.equals(dates.get(fileName))) {
                if (w != null) w.close();
                w = new BufferedWriter(new OutputStreamWriter(
                        new FileOutputStream(new File(dir, fileName + today + ".txt"), true), StandardCharsets.UTF_8));
                writers.put(fileName, w);
                dates.put(fileName, today);
            }
            w.write(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(now) + "  " + (content == null ? "" : content));
            w.newLine();
            w.flush();
        } catch (IOException e) {
            writers.remove(fileName);
        }
    }

    /** Delete log files older than {@code days} (legacy FileDeletionModule kept 3 days). */
    public void prune(int days) {
        long cutoff = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) if (f.isFile() && f.getName().endsWith(".txt") && f.lastModified() < cutoff) f.delete();
    }
}
