// Ported from timelib 2022.17 (ext/date/lib/unixtime2tm.c),
// Copyright (c) 2015-2021 Derick Rethans, Copyright (c) 2021 MongoDB,
// MIT License (see src/main/resources/META-INF/LICENSE-timelib).

package dev.phpruntime.timelib;

import java.util.Locale;

import static dev.phpruntime.timelib.TimelibConstants.*;

/** Port of {@code unixtime2tm.c}. */
public final class Unixtime2Tm {

    private Unixtime2Tm() {
    }

    /** C: {@code timelib_date_from_epoch_days} */
    public static void dateFromEpochDays(long epochDays, TimelibTime out) {
        long days, era;
        long dayOfEra, yearOfEra, dayOfYear, monthPortion;

        /* Calculate days since algorithm's epoch (0000-03-01) */
        days = epochDays + HINNANT_EPOCH_SHIFT;

        era = (days >= 0 ? days : days - DAYS_PER_ERA + 1) / DAYS_PER_ERA;
        dayOfEra = days - era * DAYS_PER_ERA;
        yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36524 - dayOfEra / 146096) / DAYS_PER_YEAR;
        long y = yearOfEra + era * YEARS_PER_ERA;
        dayOfYear = dayOfEra - (DAYS_PER_YEAR * yearOfEra + yearOfEra / 4 - yearOfEra / 100);
        monthPortion = (5 * dayOfYear + 2) / 153;
        long d = dayOfYear - (153 * monthPortion + 2) / 5 + 1;
        long m = monthPortion + (monthPortion < 10 ? 3 : -9);
        y += (m <= 2) ? 1 : 0;
        out.y = y;
        out.m = m;
        out.d = d;
    }

    /** C: {@code timelib_unixtime2date} */
    public static void unixtime2date(long ts, TimelibTime out) {
        long epochDays, t;

        epochDays = ts / SECS_PER_DAY;

        /* Adjustment for a negative time portion */
        t = ts % SECS_PER_DAY;
        epochDays += (t < 0) ? -1 : 0;

        dateFromEpochDays(epochDays, out);
    }

    /** C: {@code timelib_unixtime2gmt} */
    public static void unixtime2gmt(TimelibTime tm, long ts) {
        long remainder;
        long hours, minutes, seconds;

        unixtime2date(ts, tm);
        remainder = ts % SECS_PER_DAY;
        remainder += (remainder < 0 ? 1L : 0L) * SECS_PER_DAY;

        /* That was the date, now we do the time */
        hours = remainder / 3600;
        minutes = (remainder - hours * 3600) / 60;
        seconds = remainder % 60;

        tm.h = hours;
        tm.i = minutes;
        tm.s = seconds;
        tm.z = 0;
        tm.dst = 0;
        tm.sse = ts;
        tm.sseUptodate = 1;
        tm.timUptodate = 1;
        tm.isLocaltime = 0;
    }

    /** C: {@code timelib_update_from_sse} */
    public static void updateFromSse(TimelibTime tm) {
        long sse = tm.sse;
        int z = tm.z;
        int dst = tm.dst;

        switch (tm.zoneType) {
            case TIMELIB_ZONETYPE_ABBR:
            case TIMELIB_ZONETYPE_OFFSET:
                unixtime2gmt(tm, tm.sse + tm.z + (tm.dst * 3600L));
                break;

            case TIMELIB_ZONETYPE_ID:
                int[] offset = new int[1];
                ParseTz.getTimeZoneOffsetInfo(tm.sse, tm.tzInfo, offset, null, null);
                unixtime2gmt(tm, tm.sse + offset[0]);
                break;

            default:
                unixtime2gmt(tm, tm.sse);
                break;
        }

        tm.sse = sse;
        tm.isLocaltime = 1;
        tm.haveZone = 1;
        tm.z = z;
        tm.dst = dst;
    }

    /** C: {@code timelib_unixtime2local} */
    public static void unixtime2local(TimelibTime tm, long ts) {
        TimelibTzinfo.TimelibTimeOffset gmtOffset;
        TimelibTzinfo tz = tm.tzInfo;

        switch (tm.zoneType) {
            case TIMELIB_ZONETYPE_ABBR:
            case TIMELIB_ZONETYPE_OFFSET: {
                int z = tm.z;
                int dst = tm.dst;

                unixtime2gmt(tm, ts + tm.z + (tm.dst * 3600L));

                tm.sse = ts;
                tm.z = z;
                tm.dst = dst;
                break;
            }

            case TIMELIB_ZONETYPE_ID:
                gmtOffset = ParseTz.getTimeZoneInfo(ts, tz);
                unixtime2gmt(tm, ts + gmtOffset.offset);

                /* we need to reset the sse here as unixtime2gmt modifies it */
                tm.sse = ts;
                tm.dst = gmtOffset.isDst;
                tm.z = gmtOffset.offset;
                tm.tzInfo = tz;

                tm.tzAbbr = gmtOffset.abbr != null ? gmtOffset.abbr.toUpperCase(Locale.ROOT) : null;
                break;

            default:
                tm.isLocaltime = 0;
                tm.haveZone = 0;
                return;
        }

        tm.isLocaltime = 1;
        tm.haveZone = 1;
    }

    /** C: {@code timelib_set_timezone_from_offset} */
    public static void setTimezoneFromOffset(TimelibTime t, long utcOffset) {
        t.tzAbbr = null;
        t.z = (int) utcOffset;
        t.haveZone = 1;
        t.zoneType = TIMELIB_ZONETYPE_OFFSET;
        t.dst = 0;
        t.tzInfo = null;
    }

    /** C: {@code timelib_set_timezone_from_abbr} */
    public static void setTimezoneFromAbbr(TimelibTime t, String abbr, long utcOffset, int dst) {
        t.tzAbbr = abbr != null ? abbr.toUpperCase(Locale.ROOT) : null;
        t.z = (int) utcOffset;
        t.haveZone = 1;
        t.zoneType = TIMELIB_ZONETYPE_ABBR;
        t.dst = dst;
        t.tzInfo = null;
    }

    /** C: {@code timelib_set_timezone} */
    public static void setTimezone(TimelibTime t, TimelibTzinfo tz) {
        TimelibTzinfo.TimelibTimeOffset gmtOffset = ParseTz.getTimeZoneInfo(t.sse, tz);
        t.z = gmtOffset.offset;
        t.dst = gmtOffset.isDst;
        t.tzInfo = tz;
        t.tzAbbr = gmtOffset.abbr != null ? gmtOffset.abbr.toUpperCase(Locale.ROOT) : null;
        t.haveZone = 1;
        t.zoneType = TIMELIB_ZONETYPE_ID;
    }

    /** C: {@code timelib_apply_localtime} */
    public static int applyLocaltime(TimelibTime t, int localtime) {
        if (localtime != 0) {
            /* Converting from GMT time to local time */
            /* Check if TZ is set */
            if (t.tzInfo == null) {
                return -1;
            }
            unixtime2local(t, t.sse);
        } else {
            /* Converting from local time to GMT time */
            unixtime2gmt(t, t.sse);
        }
        return 0;
    }
}
