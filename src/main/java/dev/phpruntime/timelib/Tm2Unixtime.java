// Ported from timelib 2022.17 (ext/date/lib/tm2unixtime.c),
// Copyright (c) 2015-2019 Derick Rethans,
// MIT License (see src/main/resources/META-INF/LICENSE-timelib).

package dev.phpruntime.timelib;

import static dev.phpruntime.timelib.TimelibConstants.*;

/** Port of {@code tm2unixtime.c}. */
public final class Tm2Unixtime {

    private Tm2Unixtime() {
    }

    /*                                                  dec  jan  feb  mrt  apr  may  jun  jul  aug  sep  oct  nov  dec */
    private static final int[] DAYS_IN_MONTH_LEAP = {  31,  31,  29,  31,  30,  31,  30,  31,  31,  30,  31,  30,  31 };
    private static final int[] DAYS_IN_MONTH      = {  31,  31,  28,  31,  30,  31,  30,  31,  31,  30,  31,  30,  31 };

    private static void doRangeLimit(long start, long end, long adj, long[] ab, int ai, int bi) {
        if (ab[ai] < start) {
            long aPlus1 = ab[ai] + 1;
            ab[bi] -= (start - aPlus1) / adj + 1;
            ab[ai] += adj * ((start - aPlus1) / adj);
            ab[ai] += adj;
        }
        if (ab[ai] >= end) {
            ab[bi] += ab[ai] / adj;
            ab[ai] -= adj * (ab[ai] / adj);
        }
    }

    private static void incMonth(long[] ym) {
        ym[1]++;
        if (ym[1] > 12) {
            ym[1] -= 12;
            ym[0]++;
        }
    }

    private static void decMonth(long[] ym) {
        ym[1]--;
        if (ym[1] < 1) {
            ym[1] += 12;
            ym[0]--;
        }
    }

    private static void doRangeLimitDaysRelative(long[] baseYm, long[] ymd, long invert) {
        long leapyear;
        long days;

        doRangeLimit(1, 13, 12, baseYm, 1, 0);

        long year = baseYm[0];
        long month = baseYm[1];
        long[] ym = new long[]{year, month};

        if (invert == 0) {
            while (ymd[2] < 0) {
                decMonth(ym);
                leapyear = isLeap(ym[0]) ? 1 : 0;
                days = leapyear != 0 ? DAYS_IN_MONTH_LEAP[(int) ym[1]] : DAYS_IN_MONTH[(int) ym[1]];

                ymd[2] += days;
                ymd[1]--;
            }
        } else {
            while (ymd[2] < 0) {
                leapyear = isLeap(ym[0]) ? 1 : 0;
                days = leapyear != 0 ? DAYS_IN_MONTH_LEAP[(int) ym[1]] : DAYS_IN_MONTH[(int) ym[1]];

                ymd[2] += days;
                ymd[1]--;
                incMonth(ym);
            }
        }
    }

    private static int doRangeLimitDays(long[] ymd) {
        boolean leapyear;
        long previousMonth, previousYear;
        long daysInPreviousMonth;
        int retval = 0;
        int[] daysPerMonthCurrentYear;

        /* can jump an entire leap year period quickly */
        if (ymd[2] >= DAYS_PER_ERA || ymd[2] <= -DAYS_PER_ERA) {
            ymd[0] += YEARS_PER_ERA * (ymd[2] / DAYS_PER_ERA);
            ymd[2] -= DAYS_PER_ERA * (ymd[2] / DAYS_PER_ERA);
        }

        doRangeLimit(1, 13, 12, ymd, 1, 0);

        leapyear = isLeap(ymd[0]);
        daysPerMonthCurrentYear = leapyear ? DAYS_IN_MONTH_LEAP : DAYS_IN_MONTH;

        while (ymd[2] <= 0 && ymd[1] > 0) {
            previousMonth = ymd[1] - 1;
            if (previousMonth < 1) {
                previousMonth += 12;
                previousYear = ymd[0] - 1;
            } else {
                previousYear = ymd[0];
            }
            leapyear = isLeap(previousYear);
            daysInPreviousMonth = leapyear ? DAYS_IN_MONTH_LEAP[(int) previousMonth] : DAYS_IN_MONTH[(int) previousMonth];

            ymd[2] += daysInPreviousMonth;
            ymd[1]--;
            retval = 1;
        }
        while (ymd[2] > 0 && ymd[1] <= 12 && ymd[2] > daysPerMonthCurrentYear[(int) ymd[1]]) {
            ymd[2] -= daysPerMonthCurrentYear[(int) ymd[1]];
            ymd[1]++;
            retval = 1;
        }
        return retval;
    }

    private static void doAdjustForWeekday(TimelibTime time) {
        long currentDow, difference;

        currentDow = Dow.dayOfWeek(time.y, time.m, time.d);
        if (time.relative.weekdayBehavior == 2) {
            /* To make "this week" work, where the current DOW is a "sunday" */
            if (currentDow == 0 && time.relative.weekday != 0) {
                time.relative.weekday -= 7;
            }

            /* To make "sunday this week" work, where the current DOW is not a
             * "sunday" */
            if (time.relative.weekday == 0 && currentDow != 0) {
                time.relative.weekday = 7;
            }

            time.d -= currentDow;
            time.d += time.relative.weekday;
            return;
        }
        difference = time.relative.weekday - currentDow;
        if ((time.relative.d < 0 && difference < 0) || (time.relative.d >= 0 && difference <= -time.relative.weekdayBehavior)) {
            difference += 7;
        }
        if (time.relative.weekday >= 0) {
            time.d += difference;
        } else {
            time.d -= (7 - (Math.abs(time.relative.weekday) - currentDow));
        }
        time.relative.haveWeekdayRelative = 0;
    }

    /** C: {@code timelib_do_rel_normalize} */
    public static void doRelNormalize(TimelibTime base, TimelibRelTime rt) {
        long[] us_s = new long[]{rt.us, rt.s};
        doRangeLimit(0, 1000000, 1000000, us_s, 0, 1);
        rt.us = us_s[0]; rt.s = us_s[1];

        long[] s_i = new long[]{rt.s, rt.i};
        doRangeLimit(0, 60, 60, s_i, 0, 1);
        rt.s = s_i[0]; rt.i = s_i[1];

        long[] i_h = new long[]{rt.i, rt.h};
        doRangeLimit(0, 60, 60, i_h, 0, 1);
        rt.i = i_h[0]; rt.h = i_h[1];

        long[] h_d = new long[]{rt.h, rt.d};
        doRangeLimit(0, 24, 24, h_d, 0, 1);
        rt.h = h_d[0]; rt.d = h_d[1];

        long[] m_y = new long[]{rt.m, rt.y};
        doRangeLimit(0, 12, 12, m_y, 0, 1);
        rt.m = m_y[0]; rt.y = m_y[1];

        long[] baseYm = new long[]{base.y, base.m};
        long[] ymd = new long[]{rt.y, rt.m, rt.d};
        doRangeLimitDaysRelative(baseYm, ymd, rt.invert);
        rt.y = ymd[0]; rt.m = ymd[1]; rt.d = ymd[2];

        m_y[0] = rt.m; m_y[1] = rt.y;
        doRangeLimit(0, 12, 12, m_y, 0, 1);
        rt.m = m_y[0]; rt.y = m_y[1];
    }

    /** C: {@code timelib_do_normalize} */
    public static void doNormalize(TimelibTime time) {
        if (time.us != TIMELIB_UNSET) {
            long[] us_s = new long[]{time.us, time.s};
            doRangeLimit(0, 1000000, 1000000, us_s, 0, 1);
            time.us = us_s[0]; time.s = us_s[1];
        }
        if (time.s != TIMELIB_UNSET) {
            long[] s_i = new long[]{time.s, time.i};
            doRangeLimit(0, 60, 60, s_i, 0, 1);
            time.s = s_i[0]; time.i = s_i[1];
        }
        if (time.s != TIMELIB_UNSET) {
            long[] i_h = new long[]{time.i, time.h};
            doRangeLimit(0, 60, 60, i_h, 0, 1);
            time.i = i_h[0]; time.h = i_h[1];
        }
        if (time.s != TIMELIB_UNSET) {
            long[] h_d = new long[]{time.h, time.d};
            doRangeLimit(0, 24, 24, h_d, 0, 1);
            time.h = h_d[0]; time.d = h_d[1];
        }
        long[] m_y = new long[]{time.m, time.y};
        doRangeLimit(1, 13, 12, m_y, 0, 1);
        time.m = m_y[0]; time.y = m_y[1];

        /* Short cut if we're doing things against the Epoch */
        if (time.y == 1970 && time.m == 1) {
            Unixtime2Tm.dateFromEpochDays(time.d - 1, time);
            return;
        }

        long[] ymd = new long[]{time.y, time.m, time.d};
        do {} while (doRangeLimitDays(ymd) != 0);
        time.y = ymd[0]; time.m = ymd[1]; time.d = ymd[2];

        m_y[0] = time.m; m_y[1] = time.y;
        doRangeLimit(1, 13, 12, m_y, 0, 1);
        time.m = m_y[0]; time.y = m_y[1];
    }

    private static void doAdjustRelative(TimelibTime time) {
        if (time.relative.haveWeekdayRelative != 0) {
            doAdjustForWeekday(time);
        }
        doNormalize(time);

        if (time.haveRelative != 0) {
            time.us += time.relative.us;

            time.s += time.relative.s;
            time.i += time.relative.i;
            time.h += time.relative.h;

            time.d += time.relative.d;
            time.m += time.relative.m;
            time.y += time.relative.y;
        }

        switch (time.relative.firstLastDayOf) {
            case TIMELIB_SPECIAL_FIRST_DAY_OF_MONTH: /* first */
                time.d = 1;
                break;
            case TIMELIB_SPECIAL_LAST_DAY_OF_MONTH: /* last */
                time.d = 0;
                time.m++;
                break;
        }

        doNormalize(time);
    }

    private static void doAdjustSpecialWeekday(TimelibTime time) {
        long count, dow, rem;

        count = time.relative.special.amount;
        dow = Dow.dayOfWeek(time.y, time.m, time.d);

        /* Add increments of 5 weekdays as a week, leaving the DOW unchanged. */
        time.d += (count / 5) * 7;

        /* Deal with the remainder. */
        rem = (count % 5);

        if (count > 0) {
            if (rem == 0) {
                /* Head back to Friday if we stop on the weekend. */
                if (dow == 0) {
                    time.d -= 2;
                } else if (dow == 6) {
                    time.d -= 1;
                }
            } else if (dow == 6) {
                /* We ended up on Saturday, but there's still work to do, so move
                 * to Sunday and continue from there. */
                time.d += 1;
            } else if (dow + rem > 5) {
                /* We're on a weekday, but we're going past Friday, so skip right
                 * over the weekend. */
                time.d += 2;
            }
        } else {
            /* Completely mirror the forward direction. This also covers the 0
             * case, since if we start on the weekend, we want to move forward as
             * if we stopped there while going backwards. */
            if (rem == 0) {
                if (dow == 6) {
                    time.d += 2;
                } else if (dow == 0) {
                    time.d += 1;
                }
            } else if (dow == 0) {
                time.d -= 1;
            } else if (dow + rem < 1) {
                time.d -= 2;
            }
        }

        time.d += rem;
    }

    private static void doAdjustSpecial(TimelibTime time) {
        if (time.relative.haveSpecialRelative != 0) {
            switch (time.relative.special.type) {
                case TIMELIB_SPECIAL_WEEKDAY:
                    doAdjustSpecialWeekday(time);
                    break;
            }
        }
        doNormalize(time);
        time.relative.special.type = 0;
        time.relative.special.amount = 0;
    }

    private static void doAdjustSpecialEarly(TimelibTime time) {
        if (time.relative.haveSpecialRelative != 0) {
            switch (time.relative.special.type) {
                case TIMELIB_SPECIAL_DAY_OF_WEEK_IN_MONTH:
                    time.d = 1;
                    time.m += time.relative.m;
                    time.relative.m = 0;
                    break;
                case TIMELIB_SPECIAL_LAST_DAY_OF_WEEK_IN_MONTH:
                    time.d = 1;
                    time.m += time.relative.m + 1;
                    time.relative.m = 0;
                    break;
            }
        }
        switch (time.relative.firstLastDayOf) {
            case TIMELIB_SPECIAL_FIRST_DAY_OF_MONTH: /* first */
                time.d = 1;
                break;
            case TIMELIB_SPECIAL_LAST_DAY_OF_MONTH: /* last */
                time.d = 0;
                time.m++;
                break;
        }
        doNormalize(time);
    }

    private static void doAdjustTimezone(TimelibTime tz, TimelibTzinfo tzi) {
        switch (tz.zoneType) {
            case TIMELIB_ZONETYPE_OFFSET:
                tz.isLocaltime = 1;
                tz.sse += -tz.z;
                return;

            case TIMELIB_ZONETYPE_ABBR:
                tz.isLocaltime = 1;
                tz.sse += (-tz.z - tz.dst * (long) SECS_PER_HOUR);
                return;

            case TIMELIB_ZONETYPE_ID:
                tzi = tz.tzInfo;
                // Intentional fallthrough to default
            default: {
                int[] currentOffset = new int[1];
                long[] currentTransitionTime = new long[1];
                int[] currentIsDst = new int[1];
                int[] afterOffset = new int[1];
                long[] afterTransitionTime = new long[1];
                long adjustment;
                boolean inTransition;
                int actualOffset;
                long actualTransitionTime;

                if (tzi == null) {
                    return;
                }

                ParseTz.getTimeZoneOffsetInfo(tz.sse, tzi, currentOffset, currentTransitionTime, currentIsDst);
                ParseTz.getTimeZoneOffsetInfo(tz.sse - currentOffset[0], tzi, afterOffset, afterTransitionTime, null);
                actualOffset = afterOffset[0];
                actualTransitionTime = afterTransitionTime[0];

                if (currentOffset[0] == afterOffset[0] && tz.haveZone != 0) {
                    /* Make sure we're not missing a DST change because we don't know the actual offset yet */
                    if (currentOffset[0] >= 0 && tz.dst != 0 && currentIsDst[0] == 0) {
                        int[] earlierOffset = new int[1];
                        long[] earlierTransitionTime = new long[1];
                        ParseTz.getTimeZoneOffsetInfo(tz.sse - currentOffset[0] - 7200, tzi, earlierOffset, earlierTransitionTime, null);
                        if (earlierOffset[0] != afterOffset[0] && (tz.sse - earlierOffset[0] < afterTransitionTime[0])) {
                            actualOffset = earlierOffset[0];
                            actualTransitionTime = earlierTransitionTime[0];
                        }
                    } else if (currentOffset[0] <= 0 && currentIsDst[0] != 0 && tz.dst == 0) {
                        int[] laterOffset = new int[1];
                        long[] laterTransitionTime = new long[1];
                        ParseTz.getTimeZoneOffsetInfo(tz.sse - currentOffset[0] + 7200, tzi, laterOffset, laterTransitionTime, null);
                        if (laterOffset[0] != afterOffset[0] && (tz.sse - laterOffset[0] >= laterTransitionTime[0])) {
                            actualOffset = laterOffset[0];
                            actualTransitionTime = laterTransitionTime[0];
                        }
                    }
                }

                tz.isLocaltime = 1;

                inTransition = (
                    actualTransitionTime != Long.MIN_VALUE &&
                    ((tz.sse - actualOffset) >= (actualTransitionTime + (currentOffset[0] - actualOffset))) &&
                    ((tz.sse - actualOffset) < actualTransitionTime)
                );

                if ((currentOffset[0] != actualOffset) && !inTransition) {
                    adjustment = -actualOffset;
                } else {
                    adjustment = -currentOffset[0];
                }

                tz.sse += adjustment;
                Unixtime2Tm.setTimezone(tz, tzi);
                return;
            }
        }
    }

    /** C: {@code timelib_epoch_days_from_time} */
    public static long epochDaysFromTime(TimelibTime time) {
        long y = time.y; // Make copy, as we don't want to change the original one
        long era, yearOfEra, dayOfYear, dayOfEra;

        y -= (time.m <= 2) ? 1 : 0;
        era = (y >= 0 ? y : y - 399) / YEARS_PER_ERA;
        yearOfEra = y - era * YEARS_PER_ERA;
        dayOfYear = (153 * (time.m + (time.m > 2 ? -3 : 9)) + 2) / 5 + time.d - 1;
        dayOfEra = yearOfEra * DAYS_PER_YEAR + yearOfEra / 4 - yearOfEra / 100 + dayOfYear;

        return era * DAYS_PER_ERA + dayOfEra - HINNANT_EPOCH_SHIFT;
    }

    public static long hmsToSeconds(long h, long m, long s) {
        return (h * SECS_PER_HOUR) + (m * 60) + s;
    }

    /** C: {@code timelib_update_ts} */
    public static void updateTs(TimelibTime time, TimelibTzinfo tzi) {
        doAdjustSpecialEarly(time);
        doAdjustRelative(time);
        doAdjustSpecial(time);

        time.sse = hmsToSeconds(time.h, time.i, time.s);
        time.sse += epochDaysFromTime(time) * (SECS_PER_DAY / 2);
        time.sse += epochDaysFromTime(time) * (SECS_PER_DAY / 2);

        // This modifies time->sse, if needed
        doAdjustTimezone(time, tzi);

        time.sseUptodate = 1;
        time.haveRelative = 0;
        time.relative.haveWeekdayRelative = 0;
        time.relative.haveSpecialRelative = 0;
        time.relative.firstLastDayOf = 0;
    }
}
