// Ported from timelib 2022.17 (ext/date/lib/timelib.c),
// Copyright (c) 2015-2019 Derick Rethans, Portions copyright (c) 1998-2017 Zend Technologies Ltd.,
// MIT License (see src/main/resources/META-INF/LICENSE-timelib).

package dev.phpruntime.timelib;

import java.util.Locale;

import static dev.phpruntime.timelib.TimelibConstants.*;

/** Port of {@code timelib.c}. */
public final class Timelib {

    private Timelib() {
    }

    private static final String[] TIMELIB_ERROR_MESSAGES = {
        "No error",
        "Cannot allocate buffer for parsing",
        "Corrupt tzfile: The transitions in the file don't always increase",
        "Corrupt tzfile: The expected 64-bit preamble is missing",
        "Corrupt tzfile: No abbreviation could be found for a transition",
        "The version used in this timezone identifier is unsupported",
        "No timezone with this name could be found",
        "A 'slim' timezone file has been detected",
        "The embedded POSIX string is not valid",
        "The embedded POSIX string is empty"
    };

    /** C: {@code timelib_get_error_message} */
    public static String getErrorMessage(int errorCode) {
        if (errorCode >= 0 && errorCode < TIMELIB_ERROR_MESSAGES.length) {
            return TIMELIB_ERROR_MESSAGES[errorCode];
        }
        return "Unknown error code";
    }

    /** C: {@code timelib_time_ctor} */
    public static TimelibTime timeCtor() {
        return new TimelibTime();
    }

    /** C: {@code timelib_time_dtor} */
    public static void timeDtor(TimelibTime t) {
        // GC handles memory in Java
    }

    /** C: {@code timelib_time_compare} */
    public static int timeCompare(TimelibTime t1, TimelibTime t2) {
        if (t1.sse == t2.sse) {
            if (t1.us == t2.us) {
                return 0;
            }
            return (t1.us < t2.us) ? -1 : 1;
        }
        return (t1.sse < t2.sse) ? -1 : 1;
    }

    /** C: {@code timelib_time_clone} */
    public static TimelibTime timeClone(TimelibTime orig) {
        return orig == null ? null : orig.copy();
    }

    /** C: {@code timelib_rel_time_ctor} */
    public static TimelibRelTime relTimeCtor() {
        return new TimelibRelTime();
    }

    /** C: {@code timelib_rel_time_dtor} */
    public static void relTimeDtor(TimelibRelTime t) {
        // GC handles memory in Java
    }

    /** C: {@code timelib_rel_time_clone} */
    public static TimelibRelTime relTimeClone(TimelibRelTime rel) {
        return rel == null ? null : rel.copy();
    }

    /** C: {@code timelib_time_tz_abbr_update} */
    public static void timeTzAbbrUpdate(TimelibTime tm, String tzAbbr) {
        tm.tzAbbr = tzAbbr != null ? tzAbbr.toUpperCase(Locale.ROOT) : null;
    }

    /** C: {@code timelib_time_offset_ctor} */
    public static TimelibTzinfo.TimelibTimeOffset timeOffsetCtor() {
        return new TimelibTzinfo.TimelibTimeOffset();
    }

    /** C: {@code timelib_time_offset_dtor} */
    public static void timeOffsetDtor(TimelibTzinfo.TimelibTimeOffset t) {
        // GC handles memory in Java
    }

    /** C: {@code timelib_get_tz_abbr_ptr} */
    public static String getTzAbbrPtr(TimelibTime t) {
        if (t.sseUptodate == 0) {
            Tm2Unixtime.updateTs(t, null);
        }
        return t.tzAbbr;
    }

    /** C: {@code timelib_error_container_dtor} */
    public static void errorContainerDtor(TimelibErrorContainer errors) {
        // GC handles memory in Java
    }

    /** C: {@code timelib_date_to_int} */
    public static long dateToInt(TimelibTime d, int[] error) {
        long ts = d.sse;
        if (error != null) {
            error[0] = 0;
        }
        return ts;
    }

    /** C: {@code timelib_decimal_hour_to_hms} */
    public static void decimalHourToHms(double h, int[] hour, int[] min, int[] sec) {
        boolean swap = false;
        if (h < 0) {
            swap = true;
            h = Math.abs(h);
        }

        int hFloor = (int) Math.floor(h);
        int seconds = (int) Math.floor((h - hFloor) * 3600);

        hour[0] = swap ? -hFloor : hFloor;
        min[0] = seconds / 60;
        sec[0] = seconds % 60;
    }

    /** C: {@code timelib_hms_to_decimal_hour} */
    public static double hmsToDecimalHour(int hour, int min, int sec) {
        if (hour >= 0) {
            return ((double) hour + (double) min / 60 + (double) sec / 3600);
        } else {
            return ((double) hour - (double) min / 60 - (double) sec / 3600);
        }
    }

    /** C: {@code timelib_hmsf_to_decimal_hour} */
    public static double hmsfToDecimalHour(long hour, long min, long sec, long us) {
        if (hour >= 0) {
            return ((double) hour + (double) min / MINS_PER_HOUR + (double) sec / SECS_PER_HOUR) + (double) us / USECS_PER_HOUR;
        } else {
            return ((double) hour - (double) min / MINS_PER_HOUR - (double) sec / SECS_PER_HOUR) - (double) us / USECS_PER_HOUR;
        }
    }

    /** C: {@code timelib_hms_to_seconds} */
    public static long hmsToSeconds(long h, long m, long s) {
        return (h * SECS_PER_HOUR) + (m * 60) + s;
    }

    private static int timelibToLower(int c) {
        if (c >= 'A' && c <= 'Z') {
            return c + ('a' - 'A');
        }
        return c & 0xff;
    }

    /** C: {@code timelib_strcasecmp} */
    public static int strcasecmp(String s1, String s2) {
        if (s1 == s2) {
            return 0;
        }
        if (s1 == null) return -1;
        if (s2 == null) return 1;

        int len1 = s1.length();
        int len2 = s2.length();
        int len = Math.min(len1, len2);

        for (int i = 0; i < len; i++) {
            int c1 = timelibToLower(s1.charAt(i));
            int c2 = timelibToLower(s2.charAt(i));
            if (c1 != c2) {
                return c1 - c2;
            }
        }
        return len1 - len2;
    }

    /** C: {@code timelib_strncasecmp} */
    public static int strncasecmp(String s1, String s2, int length) {
        if (s1 == s2 || length == 0) {
            return 0;
        }
        if (s1 == null) return -1;
        if (s2 == null) return 1;

        int len1 = s1.length();
        int len2 = s2.length();
        int len = Math.min(length, Math.min(len1, len2));

        for (int i = 0; i < len; i++) {
            int c1 = timelibToLower(s1.charAt(i));
            int c2 = timelibToLower(s2.charAt(i));
            if (c1 != c2) {
                return c1 - c2;
            }
        }
        return Math.min(length, len1) - Math.min(length, len2);
    }
}
