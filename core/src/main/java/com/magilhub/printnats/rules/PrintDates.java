package com.magilhub.printnats.rules;

import org.threeten.bp.Instant;
import org.threeten.bp.LocalDateTime;
import org.threeten.bp.OffsetDateTime;
import org.threeten.bp.ZoneId;
import org.threeten.bp.ZonedDateTime;
import org.threeten.bp.format.DateTimeFormatter;
import org.threeten.bp.format.DateTimeParseException;

import java.util.Locale;
import java.util.TimeZone;

/**
 * Date formatting as the JS print code does it: {@code new Date(isoString)} (UTC when the string carries
 * Z/offset, device-local otherwise), then date-fns {@code format(...)} in the device zone, or
 * {@code formatInOutletZone} in the outlet's {@code timeZoneCd}. Patterns are date-fns tokens.
 */
public final class PrintDates {
    private final ZoneId deviceZone;

    public PrintDates(ZoneId deviceZone) {
        this.deviceZone = deviceZone;
    }

    public static PrintDates systemDefault() {
        return new PrintDates(ZoneId.of(TimeZone.getDefault().getID()));
    }

    public ZoneId deviceZone() {
        return deviceZone;
    }

    /** JS {@code a.split('T')[0] + 'T' + b.split('T')[1]} — "undefined" parts make an invalid date. */
    public static String merge(String datePart, String timePart) {
        String d = datePart == null ? "undefined" : datePart.split("T", -1)[0];
        String[] t = timePart == null ? new String[]{"undefined"} : timePart.split("T", -1);
        String tp = t.length > 1 ? t[1] : "undefined";
        return d + "T" + tp;
    }

    /** JS new Date(s) for ISO strings; null for an invalid date. */
    public Instant parse(String s) {
        if (s == null || s.contains("undefined") || s.contains("null")) return null;
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException e) {
            // no offset → local time
        }
        try {
            return ZonedDateTime.parse(s).toInstant();
        } catch (DateTimeParseException e) {
            // fall through
        }
        try {
            return LocalDateTime.parse(s).atZone(deviceZone).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * date-fns format in the device zone. Legacy JS threw (aborting the whole print) on an invalid date;
     * this returns "" instead (fix-forward, see docs/parity-matrix.md).
     */
    public String format(Instant instant, String dateFnsPattern) {
        return format(instant, dateFnsPattern, deviceZone);
    }

    /** formatInOutletZone(...) ?? format(...): outlet zone when known, else device zone. */
    public String formatOutletOrDevice(Instant instant, String dateFnsPattern, String timeZoneCd) {
        ZoneId zone = deviceZone;
        if (timeZoneCd != null && !timeZoneCd.isEmpty()) {
            try {
                zone = ZoneId.of(timeZoneCd);
            } catch (RuntimeException e) {
                zone = deviceZone;
            }
        }
        return format(instant, dateFnsPattern, zone);
    }

    static String format(Instant instant, String dateFnsPattern, ZoneId zone) {
        if (instant == null) return "";
        return DateTimeFormatter.ofPattern(toJavaPattern(dateFnsPattern), Locale.US).format(instant.atZone(zone));
    }

    /** The date-fns tokens used by the print code map 1:1 except 'a' (AM/PM — same in Java, Locale.US). */
    static String toJavaPattern(String p) {
        return p;
    }

    /** moment().isDST() in the device zone. */
    public boolean isDstNow() {
        return TimeZone.getTimeZone(deviceZone.getId()).inDaylightTime(new java.util.Date());
    }

    /**
     * pickUpTime: moment(new Date(`1970-01-01T${etaTime.split('T')[1]}`)).format('LT'), +1 h when isDST().
     * moment 'LT' (en) = "h:mm A".
     */
    public String pickUpTime(String etaTime) {
        if (etaTime == null) return "";
        String[] parts = etaTime.split("T", -1);
        Instant i = parse("1970-01-01T" + (parts.length > 1 ? parts[1] : "undefined"));
        if (i == null) return "Invalid date"; // moment prints this for an invalid date
        ZonedDateTime z = i.atZone(deviceZone);
        if (isDstNow()) z = z.plusHours(1);
        return DateTimeFormatter.ofPattern("h:mm a", Locale.US).format(z);
    }

    public Instant now() {
        return Instant.now();
    }
}
