package dev.phpruntime.builtins;

import dev.phpruntime.PhpContext;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpOps;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.Year;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.IsoFields;
import java.util.Locale;
import java.util.Set;

/** date, gmdate, mktime, gmmktime, checkdate and the default timezone. */
final class DateFunctions {

    static final ZoneId UTC = ZoneId.of("UTC");

    /**
     * Abbreviations the IANA database (and so PHP) still uses. For other zones tzdata prints a
     * numeric abbreviation such as "+04", while Java's CLDR names invent letters ("GST").
     */
    private static final Set<String> TZDATA_ABBREVIATIONS = Set.of(
            "UTC", "GMT", "BST", "IST", "WET", "WEST", "CET", "CEST", "MET", "MEST", "EET", "EEST", "MSK",
            "EST", "EDT", "CST", "CDT", "MST", "MDT", "PST", "PDT", "AKST", "AKDT", "HST", "HDT", "AST", "ADT",
            "NST", "NDT", "JST", "KST", "HKT", "PKT", "WIB", "WITA", "WIT", "SAST", "CAT", "EAT", "WAT",
            "AEST", "AEDT", "ACST", "ACDT", "AWST", "NZST", "NZDT", "IDT", "ChST", "SST");

    private static final DateTimeFormatter ABBREVIATION = DateTimeFormatter.ofPattern("z", Locale.ENGLISH);

    private DateFunctions() {
    }

    static void register() {
        Builtins.def("date", 1, 2, (ctx, a) -> {
            ContextState st = ContextState.of(ctx);
            return format(PhpOps.toStr(a[0]), at(timestamp(a, 1), st.timezone), st.timezoneName, false);
        });
        Builtins.def("gmdate", 1, 2, (ctx, a) -> format(PhpOps.toStr(a[0]), at(timestamp(a, 1), UTC), "UTC", true));
        Builtins.def("mktime", 1, 6, (ctx, a) -> mktime(a, ContextState.of(ctx).timezone));
        Builtins.def("gmmktime", 1, 6, (ctx, a) -> mktime(a, UTC));
        Builtins.def("checkdate", 3, 3, (ctx, a) -> {
            long m = PhpOps.toLong(a[0]);
            long d = PhpOps.toLong(a[1]);
            long y = PhpOps.toLong(a[2]);
            if (y < 1 || y > 32767 || m < 1 || m > 12 || d < 1) {
                return false;
            }
            return d <= java.time.YearMonth.of((int) y, (int) m).lengthOfMonth();
        });
        Builtins.def("date_default_timezone_set", 1, 1, (ctx, a) -> {
            String name = PhpOps.toStr(a[0]);
            ZoneId zone = zone(name);
            if (zone == null) {
                ctx.warning("date_default_timezone_set(): Timezone ID '" + name + "' is invalid", 0);
                return false;
            }
            ContextState st = ContextState.of(ctx);
            st.timezone = zone;
            st.timezoneName = name;
            return true;
        });
        Builtins.def("date_default_timezone_get", 0, 0, (ctx, a) -> ContextState.of(ctx).timezoneName);
    }

    /** The default timezone of a request, for other builtins that need local time. */
    static ZoneId timezone(PhpContext ctx) {
        return ContextState.of(ctx).timezone;
    }

    private static ZoneId zone(String name) {
        if (name.equalsIgnoreCase("UTC")) {
            return UTC;
        }
        try {
            return ZoneId.of(name);
        } catch (DateTimeException e) {
            for (String id : ZoneId.getAvailableZoneIds()) {
                if (id.equalsIgnoreCase(name)) {
                    return ZoneId.of(id);
                }
            }
            return null;
        }
    }

    private static long timestamp(Object[] a, int i) {
        return a.length > i && a[i] != PhpNull.INSTANCE ? PhpOps.toLong(a[i]) : System.currentTimeMillis() / 1000;
    }

    private static ZonedDateTime at(long ts, ZoneId zone) {
        return ZonedDateTime.ofInstant(Instant.ofEpochSecond(ts), zone);
    }

    /** mktime: missing arguments come from the current time; out-of-range values roll over. */
    private static long mktime(Object[] a, ZoneId zone) {
        ZonedDateTime now = ZonedDateTime.now(zone);
        long hour = arg(a, 0, now.getHour());
        long minute = arg(a, 1, now.getMinute());
        long second = arg(a, 2, now.getSecond());
        long month = arg(a, 3, now.getMonthValue());
        long day = arg(a, 4, now.getDayOfMonth());
        long year = arg(a, 5, now.getYear());
        if (year >= 0 && year < 70) {
            year += 2000;
        } else if (year >= 70 && year <= 100) {
            year += 1900;
        }
        LocalDateTime t = LocalDateTime.of((int) year, 1, 1, 0, 0)
                .plusMonths(month - 1).plusDays(day - 1).plusHours(hour).plusMinutes(minute).plusSeconds(second);
        return t.atZone(zone).toEpochSecond();
    }

    private static long arg(Object[] a, int i, long dflt) {
        return a.length > i && a[i] != PhpNull.INSTANCE ? PhpOps.toLong(a[i]) : dflt;
    }

    private static String pad2(long v) {
        return v < 10 ? "0" + v : Long.toString(v);
    }

    private static String offset(ZonedDateTime t, boolean colon) {
        int secs = t.getOffset().getTotalSeconds();
        int abs = Math.abs(secs);
        return (secs < 0 ? "-" : "+") + pad2(abs / 3600) + (colon ? ":" : "") + pad2(abs / 60 % 60);
    }

    private static String abbreviation(ZonedDateTime t, boolean gmt) {
        if (gmt) {
            return "GMT";
        }
        if (t.getZone().equals(UTC)) {
            return "UTC";
        }
        String name = ABBREVIATION.format(t);
        if (TZDATA_ABBREVIATIONS.contains(name)) {
            return name;
        }
        int secs = t.getOffset().getTotalSeconds();
        int abs = Math.abs(secs);
        return (secs < 0 ? "-" : "+") + pad2(abs / 3600) + (abs / 60 % 60 != 0 ? pad2(abs / 60 % 60) : "");
    }

    private static String suffix(int day) {
        if (day >= 11 && day <= 13) {
            return "th";
        }
        return switch (day % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
    }

    /** PHP's date() format characters. */
    static String format(String format, ZonedDateTime t, String zoneName, boolean gmt) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < format.length(); i++) {
            char c = format.charAt(i);
            switch (c) {
                case 'd' -> sb.append(pad2(t.getDayOfMonth()));
                case 'D' -> sb.append(t.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH));
                case 'j' -> sb.append(t.getDayOfMonth());
                case 'l' -> sb.append(t.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH));
                case 'N' -> sb.append(t.getDayOfWeek().getValue());
                case 'S' -> sb.append(suffix(t.getDayOfMonth()));
                case 'w' -> sb.append(t.getDayOfWeek().getValue() % 7);
                case 'z' -> sb.append(t.getDayOfYear() - 1);
                case 'W' -> sb.append(pad2(t.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)));
                case 'F' -> sb.append(t.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH));
                case 'm' -> sb.append(pad2(t.getMonthValue()));
                case 'M' -> sb.append(t.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH));
                case 'n' -> sb.append(t.getMonthValue());
                case 't' -> sb.append(t.toLocalDate().lengthOfMonth());
                case 'L' -> sb.append(Year.isLeap(t.getYear()) ? 1 : 0);
                case 'o' -> sb.append(t.get(IsoFields.WEEK_BASED_YEAR));
                case 'Y' -> sb.append(t.getYear());
                case 'y' -> sb.append(pad2(Math.floorMod(t.getYear(), 100)));
                case 'a' -> sb.append(t.getHour() < 12 ? "am" : "pm");
                case 'A' -> sb.append(t.getHour() < 12 ? "AM" : "PM");
                case 'B' -> {
                    int beat = (int) (Math.floorMod(t.toEpochSecond() + 3600, 86400) / 86.4);
                    sb.append(String.format("%03d", beat % 1000));
                }
                case 'g' -> sb.append((t.getHour() + 11) % 12 + 1);
                case 'G' -> sb.append(t.getHour());
                case 'h' -> sb.append(pad2((t.getHour() + 11) % 12 + 1));
                case 'H' -> sb.append(pad2(t.getHour()));
                case 'i' -> sb.append(pad2(t.getMinute()));
                case 's' -> sb.append(pad2(t.getSecond()));
                case 'u' -> sb.append("000000");
                case 'v' -> sb.append("000");
                case 'e' -> sb.append(zoneName);
                case 'I' -> sb.append(t.getZone().getRules().isDaylightSavings(t.toInstant()) ? 1 : 0);
                case 'O' -> sb.append(offset(t, false));
                case 'P' -> sb.append(offset(t, true));
                // "Z" only for UTC itself, not for zones that happen to be at +00:00 (London in winter)
                case 'p' -> sb.append(gmt || abbreviation(t, false).equals("UTC") ? "Z" : offset(t, true));
                case 'T' -> sb.append(abbreviation(t, gmt));
                case 'Z' -> sb.append(t.getOffset().getTotalSeconds());
                case 'c' -> sb.append(format("Y-m-d\\TH:i:sP", t, zoneName, gmt));
                case 'r' -> sb.append(format("D, d M Y H:i:s O", t, zoneName, gmt));
                case 'U' -> sb.append(t.toEpochSecond());
                case '\\' -> {
                    if (i + 1 < format.length()) {
                        sb.append(format.charAt(++i));
                    }
                }
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
