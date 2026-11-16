// Ported from timelib 2022.17 (ext/date/lib/unixtime2tm.c),
// Copyright (c) 2015-2021 Derick Rethans, Copyright (c) 2017-2019,2021 MongoDB, Inc.,
// MIT License (see src/main/resources/META-INF/LICENSE-timelib).

package dev.phpruntime.timelib;

import static dev.phpruntime.timelib.TimelibConstants.*;

/**
 * Port of {@code unixtime2tm.c}.
 *
 * <p>Phase A provides {@code dateFromEpochDays}, {@code unixtime2date}, and {@code unixtime2gmt}
 * needed by {@link ParsePosix}. Remaining functions are ported in Phase B.
 */
public final class Unixtime2Tm {

    private Unixtime2Tm() {
    }

    /** C: {@code timelib_date_from_epoch_days} */
    public static void dateFromEpochDays(long epochDays, TimelibTime out) {
        long days = epochDays + HINNANT_EPOCH_SHIFT;
        long era = (days >= 0 ? days : days - DAYS_PER_ERA + 1) / DAYS_PER_ERA;
        long dayOfEra = days - era * DAYS_PER_ERA;
        long yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36524 - dayOfEra / 146096) / DAYS_PER_YEAR;
        long y = yearOfEra + era * YEARS_PER_ERA;
        long dayOfYear = dayOfEra - (DAYS_PER_YEAR * yearOfEra + yearOfEra / 4 - yearOfEra / 100);
        long monthPortion = (5 * dayOfYear + 2) / 153;
        long d = dayOfYear - (153 * monthPortion + 2) / 5 + 1;
        long m = monthPortion + (monthPortion < 10 ? 3 : -9);
        y += (m <= 2) ? 1 : 0;
        out.y = y;
        out.m = m;
        out.d = d;
    }

    /** C: {@code timelib_unixtime2date} */
    public static void unixtime2date(long ts, TimelibTime out) {
        long epochDays = ts / SECS_PER_DAY;
        long t = ts % SECS_PER_DAY;
        epochDays += (t < 0) ? -1 : 0;
        dateFromEpochDays(epochDays, out);
    }

    /** C: {@code timelib_unixtime2gmt} */
    public static void unixtime2gmt(TimelibTime tm, long ts) {
        unixtime2date(ts, tm);
        long remainder = ts % SECS_PER_DAY;
        remainder += (remainder < 0 ? 1L : 0L) * SECS_PER_DAY;

        long hours = remainder / 3600;
        long minutes = (remainder - hours * 3600) / 60;
        long seconds = remainder % 60;

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
}
