package com.magilhub.printnats.render;

import com.magilhub.printnats.model.Receipt;
import com.magilhub.printnats.spi.LogSink;

/**
 * Star KOT template dispatch — mirrors MerchantApp {@code PrintFrameworkModule} (Release-25.1 :2359):
 * "2"/"3"/"4"/"5" → that template, null/blank → "3" (SDK default), anything else → Template 1.
 */
public final class StarKotRenderer {
    private StarKotRenderer() {
    }

    /**
     * Writes the KOT into {@code sink}. Returns a skip reason when nothing should be printed
     * (the templates would otherwise emit nothing), or {@code null} when the sink holds a ticket.
     */
    public static String render(StarSink sink, Receipt receipt, String stationName, boolean isStation,
                                boolean is58mm, boolean isUtf8, int kotSpace, LogSink log, long nowMillis) {
        if (FreshnessGuard.isStale(receipt, nowMillis)) {
            return "stale: order older than 45 minutes";
        }
        String template = ThermalKotRenderer.resolveTemplate(receipt.getTemplateNo());
        switch (template) {
            case "4":
                StarKotTemplates.printStarKotT4(sink, receipt, stationName, isStation, is58mm, isUtf8, log, kotSpace);
                break;
            case "3":
                StarKotTemplates.printStarKotT3(sink, receipt, stationName, isStation, is58mm, isUtf8, log, kotSpace);
                break;
            case "2":
                StarKotTemplates.printStarKotT2(sink, receipt, stationName, isStation, is58mm, isUtf8, log, kotSpace);
                break;
            case "5":
                StarKotTemplates.printStarKotT5(sink, receipt, stationName, isStation, is58mm, isUtf8, log, kotSpace);
                break;
            default:
                StarKotTemplates.printStarKot(sink, receipt, stationName, isStation, is58mm, isUtf8, log, kotSpace);
        }
        return null;
    }
}
