package com.magilhub.printnats.queue;

/**
 * Failure message → retry decision and Failed-Print-Queue category. Ported verbatim from MerchantApp
 * {@code PrintFrameworkModule.isPhysicalFaultMessage / classifyFailureCategory} (Release-25.1), which match
 * on the user-facing strings both printer brands already produce.
 */
public final class FailureClassifier {
    public static final String CATEGORY_PERMISSION_DENIED = "PERMISSION_DENIED";
    public static final String CATEGORY_OFFLINE = "OFFLINE";
    public static final String CATEGORY_MECHANICAL = "MECHANICAL";
    public static final String CATEGORY_COVER_OPEN = "COVER_OPEN";
    public static final String CATEGORY_PAPER_OUT = "PAPER_OUT";
    public static final String CATEGORY_UNKNOWN = "UNKNOWN";

    private FailureClassifier() {
    }

    /** Faults a person has to fix — never auto-retried (includes "printer not reachable", see legacy comment). */
    public static boolean isPhysicalFault(String message) {
        if (message == null) return false;
        String m = message.toLowerCase();
        return m.contains("cover open")
                || m.contains("out of paper")
                || m.contains("paper jam")
                || m.contains("cutter error")
                || m.contains("offline")
                || m.contains("not responding")
                || m.contains("no usb printer")
                || m.contains("printer not reachable");
    }

    public static String category(String message) {
        if (message == null) return CATEGORY_UNKNOWN;
        String m = message.toLowerCase();
        if (m.contains("permission denied")) return CATEGORY_PERMISSION_DENIED;
        if (m.contains("offline") || m.contains("unreachable") || m.contains("not responding")
                || m.contains("no usb printer") || m.contains("not found") || m.contains("not paired")
                || m.contains("turned off") || m.contains("timeout") || m.contains("network")
                || m.contains("connect")) {
            return CATEGORY_OFFLINE;
        }
        if (m.contains("jam") || m.contains("cutter") || m.contains("mechanical")) return CATEGORY_MECHANICAL;
        if (m.contains("cover")) return CATEGORY_COVER_OPEN;
        if (m.contains("paper")) return CATEGORY_PAPER_OUT;
        return CATEGORY_UNKNOWN;
    }

    /**
     * Best-effort outcome for adapters that only have a message: physical faults → FAULT; a failed
     * write/read after connecting → AMBIGUOUS (bytes may have reached the printer); else CONNECTION_FAILED.
     */
    public static PrintOutcome outcomeFor(String message) {
        if (isPhysicalFault(message)) return PrintOutcome.FAULT;
        String m = message == null ? "" : message.toLowerCase();
        if (m.contains("failed to send") || m.contains("failed to read") || m.contains("write")) {
            return PrintOutcome.AMBIGUOUS;
        }
        return PrintOutcome.CONNECTION_FAILED;
    }
}
