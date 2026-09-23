package com.magilhub.printnats.queue;

/** Point-in-time health of one printer (setup screen, dashboard, wake-up). */
public final class PrinterHealth {
    public String printerId;
    /** Could we reach it at all (socket / device present / service installed)? */
    public boolean reachable;
    /** Reachable and no blocking condition (cover, paper, cutter, offline). */
    public boolean ready;
    /** Same user-facing strings as print failures ("Cover open. …", "Printer is Offline / Unreachable"), or null. */
    public String message;
    /** FailureClassifier category of {@link #message}, or null when ready. */
    public String category;
    /** false when the connection type can't report status (then {@link #ready} is a best guess). */
    public boolean statusSupported = true;
    public long checkedAt;

    public static PrinterHealth of(String printerId, boolean reachable, boolean ready, String message) {
        PrinterHealth h = new PrinterHealth();
        h.printerId = printerId;
        h.reachable = reachable;
        h.ready = ready;
        h.message = message;
        h.category = message == null ? null : FailureClassifier.category(message);
        h.checkedAt = System.currentTimeMillis();
        return h;
    }
}
