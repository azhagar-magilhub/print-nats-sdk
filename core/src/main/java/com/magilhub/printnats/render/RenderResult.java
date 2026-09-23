package com.magilhub.printnats.render;

/** Output of a renderer: printer bytes, or a reason the ticket was intentionally not printed. */
public final class RenderResult {
    public final byte[] bytes;
    public final String skipReason;
    /** e.g. "thermal-T3", for logs. */
    public final String layout;
    public final int lineCount;

    private RenderResult(byte[] bytes, String skipReason, String layout, int lineCount) {
        this.bytes = bytes;
        this.skipReason = skipReason;
        this.layout = layout;
        this.lineCount = lineCount;
    }

    public static RenderResult bytes(byte[] bytes, String layout, int lineCount) {
        return new RenderResult(bytes, null, layout, lineCount);
    }

    public static RenderResult skipped(String reason) {
        return new RenderResult(null, reason, null, 0);
    }

    public boolean isSkipped() {
        return bytes == null;
    }
}
