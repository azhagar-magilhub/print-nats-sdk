package com.magilhub.printnats.render;

/** Output of a renderer: printer bytes, or a reason the ticket was intentionally not printed. */
public final class RenderResult {
    public final byte[] bytes;
    public final String skipReason;
    /** e.g. "thermal-T3", for logs. */
    public final String layout;
    public final int lineCount;
    /**
     * Optional send pacing recorded from legacy DantSu code (receipts/EOD): chunk i is {@code bytes[chunkEnds[i-1],
     * chunkEnds[i])}, followed by a pause of {@code chunkWaitsMs[i] + chunkLength / 16} ms — exactly what
     * {@code DeviceConnection.send(addWaitingTime)} did on a live connection. null = send in one write.
     */
    public final int[] chunkEnds;
    public final int[] chunkWaitsMs;

    private RenderResult(byte[] bytes, String skipReason, String layout, int lineCount, int[] chunkEnds, int[] chunkWaitsMs) {
        this.bytes = bytes;
        this.skipReason = skipReason;
        this.layout = layout;
        this.lineCount = lineCount;
        this.chunkEnds = chunkEnds;
        this.chunkWaitsMs = chunkWaitsMs;
    }

    /** Bytes plus the legacy send pacing (see {@link #chunkEnds}). */
    public static RenderResult paced(byte[] bytes, String layout, int[] chunkEnds, int[] chunkWaitsMs) {
        return new RenderResult(bytes, null, layout, 0, chunkEnds, chunkWaitsMs);
    }

    public boolean isPaced() {
        return chunkEnds != null && chunkEnds.length > 0;
    }

    public static RenderResult bytes(byte[] bytes, String layout, int lineCount) {
        return new RenderResult(bytes, null, layout, lineCount, null, null);
    }

    public static RenderResult skipped(String reason) {
        return new RenderResult(null, reason, null, 0, null, null);
    }

    public boolean isSkipped() {
        return bytes == null;
    }
}
