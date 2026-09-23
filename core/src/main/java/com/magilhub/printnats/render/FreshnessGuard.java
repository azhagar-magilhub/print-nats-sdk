package com.magilhub.printnats.render;

import com.magilhub.printnats.model.Receipt;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * 45-minute KOT freshness guard shared by every thermal and Star KOT template: a ticket whose
 * {@code currentDate currentTime} ("MM/dd/yy hh:mm a", device-local) is 45+ minutes old is not printed.
 * An unparseable timestamp counts as epoch 0 → stale, exactly like the original code.
 */
public final class FreshnessGuard {
    public static final long MAX_AGE_MINUTES = 45;

    private FreshnessGuard() {
    }

    public static boolean isStale(Receipt receipt, long nowMillis) {
        String dateTimeString = receipt.getCurrentDate() + " " + receipt.getCurrentTime();
        long orderTimestamp = 0;
        try {
            Date date = new SimpleDateFormat("MM/dd/yy hh:mm a").parse(dateTimeString);
            orderTimestamp = date.getTime();
        } catch (ParseException ignored) {
            // matches original behaviour: treated as epoch 0
        }
        return TimeUnit.MILLISECONDS.toMinutes(nowMillis - orderTimestamp) >= MAX_AGE_MINUTES;
    }
}
