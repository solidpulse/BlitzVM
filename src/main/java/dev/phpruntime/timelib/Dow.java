// Ported from timelib 2022.17 (ext/date/lib/dow.c),
// Copyright (c) 2015-2019 Derick Rethans,
// MIT License (see src/main/resources/META-INF/LICENSE-timelib).

package dev.phpruntime.timelib;

import static dev.phpruntime.timelib.TimelibConstants.isLeap;

/** Port of {@code dow.c}. */
public final class Dow {

    private Dow() {
    }

    private static final int[] M_TABLE_COMMON = { -1, 0, 3, 3, 6, 1, 4, 6, 2, 5, 0, 3, 5 }; /* 1 = jan */
    private static final int[] M_TABLE_LEAP =   { -1, 6, 2, 3, 6, 1, 4, 6, 2, 5, 0, 3, 5 }; /* 1 = jan */

    /** C: {@code positive_mod} */
    private static long positiveMod(long x, long y) {
        long tmp = x % y;
        if (tmp < 0) {
            tmp += y;
        }
        return tmp;
    }

    /** C: {@code century_value} */
    private static long centuryValue(long j) {
        return 6 - positiveMod(j, 4) * 2;
    }

    /** C: {@code timelib_day_of_week_ex} */
    private static long dayOfWeekEx(long y, long m, long d, int iso) {
        long c1, y1, m1, dow;

        /* Only valid for Gregorian calendar, commented out as we don't handle
         * Julian calendar. We just return the 'wrong' day of week to be
         * consistent. */
        c1 = centuryValue(positiveMod(y, 400) / 100);
        y1 = positiveMod(y, 100);
        int mi = (m >= 1 && m <= 12) ? (int) m : 0;
        m1 = isLeap(y) ? M_TABLE_LEAP[mi] : M_TABLE_COMMON[mi];
        dow = positiveMod((c1 + y1 + m1 + (y1 / 4) + d), 7);
        if (iso != 0) {
            if (dow == 0) {
                dow = 7;
            }
        }
        return dow;
    }

    /** C: {@code timelib_day_of_week} */
    public static long dayOfWeek(long y, long m, long d) {
        return dayOfWeekEx(y, m, d, 0);
    }

    /** C: {@code timelib_iso_day_of_week} */
    public static long isoDayOfWeek(long y, long m, long d) {
        return dayOfWeekEx(y, m, d, 1);
    }

                                                        /*     jan  feb  mar  apr  may  jun  jul  aug  sep  oct  nov  dec */
    private static final int[] D_TABLE_COMMON  = {  0,   0,  31,  59,  90, 120, 151, 181, 212, 243, 273, 304, 334 };
    private static final int[] D_TABLE_LEAP    = {  0,   0,  31,  60,  91, 121, 152, 182, 213, 244, 274, 305, 335 };
    private static final int[] ML_TABLE_COMMON = {  0,  31,  28,  31,  30,  31,  30,  31,  31,  30,  31,  30,  31 };
    private static final int[] ML_TABLE_LEAP   = {  0,  31,  29,  31,  30,  31,  30,  31,  31,  30,  31,  30,  31 };

    /** C: {@code timelib_day_of_year} */
    public static long dayOfYear(long y, long m, long d) {
        int mi = (m >= 1 && m <= 12) ? (int) m : 0;
        return (isLeap(y) ? D_TABLE_LEAP[mi] : D_TABLE_COMMON[mi]) + d - 1;
    }

    /** C: {@code timelib_days_in_month} */
    public static long daysInMonth(long y, long m) {
        int mi = (m >= 1 && m <= 12) ? (int) m : 0;
        return isLeap(y) ? ML_TABLE_LEAP[mi] : ML_TABLE_COMMON[mi];
    }

    /** C: {@code timelib_isoweek_from_date} */
    public static void isoweekFromDate(long y, long m, long d, long[] iw, long[] iy) {
        boolean yLeap, prevYLeap;
        long doy, jan1weekday, weekday;

        yLeap = isLeap(y);
        prevYLeap = isLeap(y - 1);
        doy = dayOfYear(y, m, d) + 1;
        if (yLeap && m > 2) {
            doy++;
        }
        jan1weekday = dayOfWeek(y, 1, 1);
        weekday = dayOfWeek(y, m, d);
        if (weekday == 0) weekday = 7;
        if (jan1weekday == 0) jan1weekday = 7;
        /* Find if Y M D falls in YearNumber Y-1, WeekNumber 52 or 53 */
        if (doy <= (8 - jan1weekday) && jan1weekday > 4) {
            iy[0] = y - 1;
            if (jan1weekday == 5 || (jan1weekday == 6 && prevYLeap)) {
                iw[0] = 53;
            } else {
                iw[0] = 52;
            }
        } else {
            iy[0] = y;
        }
        /* 8. Find if Y M D falls in YearNumber Y+1, WeekNumber 1 */
        if (iy[0] == y) {
            int i = yLeap ? 366 : 365;
            if ((i - (doy - (yLeap ? 1 : 0))) < (4 - weekday)) {
                iy[0] = y + 1;
                iw[0] = 1;
                return;
            }
        }
        /* 9. Find if Y M D falls in YearNumber Y, WeekNumber 1 through 53 */
        if (iy[0] == y) {
            long j = doy + (7 - weekday) + (jan1weekday - 1);
            iw[0] = j / 7;
            if (jan1weekday > 4) {
                iw[0] -= 1;
            }
        }
    }

    /** C: {@code timelib_isodate_from_date} */
    public static void isodateFromDate(long y, long m, long d, long[] iy, long[] iw, long[] id) {
        isoweekFromDate(y, m, d, iw, iy);
        id[0] = dayOfWeekEx(y, m, d, 1);
    }

    /** C: {@code timelib_daynr_from_weeknr} */
    public static long daynrFromWeeknr(long iy, long iw, long id) {
        long dow, day;

        /* Figure out the dayofweek for y-1-1 */
        dow = dayOfWeek(iy, 1, 1);
        /* then use that to figure out the offset for day 1 of week 1 */
        day = 0 - (dow > 4 ? dow - 7 : dow);

        /* Add weeks and days */
        return day + ((iw - 1) * 7) + id;
    }

    /** C: {@code timelib_date_from_isodate} */
    public static void dateFromIsodate(long iy, long iw, long id, long[] y, long[] m, long[] d) {
        long daynr = daynrFromWeeknr(iy, iw, id) + 1;
        int[] table;
        boolean isLeapYear;

        // Invariant: isLeapYear == timelib_is_leap(*y)
        y[0] = iy;
        isLeapYear = isLeap(y[0]);

        // Establish invariant that daynr >= 0
        while (daynr <= 0) {
            y[0] -= 1;
            daynr += (isLeapYear = isLeap(y[0])) ? 366 : 365;
        }

        // Establish invariant that daynr <= number of days in *yr
        while (daynr > (isLeapYear ? 366 : 365)) {
            daynr -= isLeapYear ? 366 : 365;
            y[0] += 1;
            isLeapYear = isLeap(y[0]);
        }

        table = isLeapYear ? ML_TABLE_LEAP : ML_TABLE_COMMON;

        // Establish invariant that daynr <= number of days in *m
        m[0] = 1;
        while (daynr > table[(int) m[0]]) {
            daynr -= table[(int) m[0]];
            m[0] += 1;
        }

        d[0] = daynr;
    }

    /** C: {@code timelib_valid_time} */
    public static int validTime(long h, long i, long s) {
        if (h < 0 || h > 23 || i < 0 || i > 59 || s < 0 || s > 59) {
            return 0;
        }
        return 1;
    }

    /** C: {@code timelib_valid_date} */
    public static int validDate(long y, long m, long d) {
        if (m < 1 || m > 12 || d < 1 || d > daysInMonth(y, m)) {
            return 0;
        }
        return 1;
    }
}
