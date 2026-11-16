// Ported from timelib 2022.17 (ext/date/lib/interval.c),
// Copyright (c) 2015-2019 Derick Rethans,
// MIT License (see src/main/resources/META-INF/LICENSE-timelib).

package dev.phpruntime.timelib;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import static dev.phpruntime.timelib.TimelibConstants.*;

/**
 * Port of {@code interval.c} ({@code timelib_diff}, {@code timelib_add}, {@code timelib_sub},
 * and wall-clock variants).
 */
public final class Interval {

    private Interval() {
    }

    private static void swapTimes(TimelibTime[] pair, TimelibRelTime rt) {
        TimelibTime swp = pair[1];
        pair[1] = pair[0];
        pair[0] = swp;
        rt.invert = 1;
    }

    private static void sortOldToNew(TimelibTime[] pair, TimelibRelTime rt) {
        TimelibTime one = pair[0];
        TimelibTime two = pair[1];

        // Check whether date/times need to be inverted. If both times are
        // TIMELIB_ZONETYPE_ID times with the same TZID, we use the y-s + us fields.
        if (one.zoneType == TIMELIB_ZONETYPE_ID && two.zoneType == TIMELIB_ZONETYPE_ID &&
                one.tzInfo != null && two.tzInfo != null && one.tzInfo.name.equals(two.tzInfo.name)) {
            if ((one.y > two.y) ||
                (one.y == two.y && one.m > two.m) ||
                (one.y == two.y && one.m == two.m && one.d > two.d) ||
                (one.y == two.y && one.m == two.m && one.d == two.d && one.h > two.h) ||
                (one.y == two.y && one.m == two.m && one.d == two.d && one.h == two.h && one.i > two.i) ||
                (one.y == two.y && one.m == two.m && one.d == two.d && one.h == two.h && one.i == two.i && one.s > two.s) ||
                (one.y == two.y && one.m == two.m && one.d == two.d && one.h == two.h && one.i == two.i && one.s == two.s && one.us > two.us)) {
                swapTimes(pair, rt);
            }
            return;
        }

        // Fall back to using the SSE instead to rearrange
        if ((one.sse > two.sse) || (one.sse == two.sse && one.us > two.us)) {
            swapTimes(pair, rt);
        }
    }

    public static int diffDays(TimelibTime one, TimelibTime two) {
        int days = 0;

        if (ParseTz.sameTimezone(one, two)) {
            TimelibTime earliest;
            TimelibTime latest;

            if (Timelib.timeCompare(one, two) < 0) {
                earliest = one;
                latest = two;
            } else {
                earliest = two;
                latest = one;
            }
            double earliestTime = Timelib.hmsfToDecimalHour(earliest.h, earliest.i, earliest.s, earliest.us);
            double latestTime = Timelib.hmsfToDecimalHour(latest.h, latest.i, latest.s, latest.us);

            days = (int) Math.abs(Tm2Unixtime.epochDaysFromTime(one) - Tm2Unixtime.epochDaysFromTime(two));
            if (latestTime < earliestTime && days > 0) {
                days--;
            }
        } else {
            days = (int) Math.abs((double) (one.sse - two.sse) / 86400.0);
        }

        return days;
    }

    private static TimelibRelTime diffWithTzid(TimelibTime oneIn, TimelibTime twoIn) {
        TimelibRelTime rt = new TimelibRelTime();
        rt.invert = 0;

        TimelibTime[] pair = new TimelibTime[] { oneIn, twoIn };
        sortOldToNew(pair, rt);
        TimelibTime one = pair[0];
        TimelibTime two = pair[1];

        // Calculate correction for UTC offset changes between first and second SSE
        long dstCorr = two.z - one.z;
        long dstHCorr = dstCorr / 3600;
        long dstMCorr = (dstCorr % 3600) / 60;

        rt.y = two.y - one.y;
        rt.m = two.m - one.m;
        rt.d = two.d - one.d;
        rt.h = two.h - one.h;
        rt.i = two.i - one.i;
        rt.s = two.s - one.s;
        rt.us = two.us - one.us;

        rt.days = diffDays(one, two);

        // Fall Back: Cater for transition period, where rt.invert is 0, but there are negative numbers
        if (two.sse < one.sse) {
            long flipped = Math.abs((rt.i * 60) + (rt.s) - dstCorr);
            rt.h = flipped / SECS_PER_HOUR;
            rt.i = (flipped - rt.h * SECS_PER_HOUR) / 60;
            rt.s = flipped % 60;

            rt.invert = 1 - rt.invert;
        }

        Tm2Unixtime.doRelNormalize(rt.invert != 0 ? one : two, rt);

        int[] transOffset = new int[1];
        long[] transTransitionTime = new long[1];

        if (one.dst == 1 && two.dst == 0) { // Fall Back
            if (two.tzInfo != null) {
                if ((two.sse - one.sse + dstCorr) < SECS_PER_DAY) {
                    rt.h -= dstHCorr;
                    rt.i -= dstMCorr;
                }
            }
        } else if (one.dst == 0 && two.dst == 1) { // Spring Forward
            if (two.tzInfo != null) {
                boolean success = ParseTz.getTimeZoneOffsetInfo(two.sse, two.tzInfo, transOffset, transTransitionTime, null);

                if (success &&
                        !((one.sse + SECS_PER_DAY > transTransitionTime[0]) && (one.sse + SECS_PER_DAY <= (transTransitionTime[0] + dstCorr))) &&
                        two.sse >= transTransitionTime[0] &&
                        ((two.sse - one.sse + dstCorr) % SECS_PER_DAY) > (two.sse - transTransitionTime[0])) {
                    rt.h -= dstHCorr;
                    rt.i -= dstMCorr;
                }
            }
        } else if (two.sse - one.sse >= SECS_PER_DAY) {
            // Check whether we're in the period to the next transition time
            if (two.tzInfo != null && ParseTz.getTimeZoneOffsetInfo(two.sse - two.z, two.tzInfo, transOffset, transTransitionTime, null)) {
                dstCorr = one.z - transOffset[0];

                if (two.sse >= transTransitionTime[0] - dstCorr && two.sse < transTransitionTime[0]) {
                    rt.d--;
                    rt.h = 24;
                }
            }
        }

        return rt;
    }

    /** C: {@code timelib_diff} */
    @TruffleBoundary
    public static TimelibRelTime diff(TimelibTime oneIn, TimelibTime twoIn) {
        if (oneIn.zoneType == TIMELIB_ZONETYPE_ID && twoIn.zoneType == TIMELIB_ZONETYPE_ID &&
                oneIn.tzInfo != null && twoIn.tzInfo != null && oneIn.tzInfo.name.equals(twoIn.tzInfo.name)) {
            return diffWithTzid(oneIn, twoIn);
        }

        TimelibRelTime rt = new TimelibRelTime();
        rt.invert = 0;

        TimelibTime[] pair = new TimelibTime[] { oneIn, twoIn };
        sortOldToNew(pair, rt);
        TimelibTime one = pair[0];
        TimelibTime two = pair[1];

        rt.y = two.y - one.y;
        rt.m = two.m - one.m;
        rt.d = two.d - one.d;
        rt.h = two.h - one.h;
        if (one.zoneType != TIMELIB_ZONETYPE_ID) {
            rt.h += one.dst;
        }
        if (two.zoneType != TIMELIB_ZONETYPE_ID) {
            rt.h -= two.dst;
        }
        rt.i = two.i - one.i;
        rt.s = two.s - one.s - two.z + one.z;
        rt.us = two.us - one.us;

        rt.days = diffDays(one, two);

        Tm2Unixtime.doRelNormalize(rt.invert != 0 ? one : two, rt);

        return rt;
    }

    /** C: {@code timelib_add} */
    @TruffleBoundary
    public static TimelibTime add(TimelibTime oldTime, TimelibRelTime interval) {
        int bias = 1;
        TimelibTime t = Timelib.timeClone(oldTime);

        if (interval.haveWeekdayRelative != 0 || interval.haveSpecialRelative != 0) {
            t.relative = interval.copy();
        } else {
            if (interval.invert != 0) {
                bias = -1;
            }
            t.relative = new TimelibRelTime();
            t.relative.y = interval.y * bias;
            t.relative.m = interval.m * bias;
            t.relative.d = interval.d * bias;
            t.relative.h = interval.h * bias;
            t.relative.i = interval.i * bias;
            t.relative.s = interval.s * bias;
            t.relative.us = interval.us * bias;
        }
        t.haveRelative = 1;
        t.sseUptodate = 0;

        Tm2Unixtime.updateTs(t, null);
        Unixtime2Tm.updateFromSse(t);
        t.haveRelative = 0;

        return t;
    }

    /** C: {@code timelib_sub} */
    @TruffleBoundary
    public static TimelibTime sub(TimelibTime oldTime, TimelibRelTime interval) {
        int bias = 1;
        TimelibTime t = Timelib.timeClone(oldTime);

        if (interval.invert != 0) {
            bias = -1;
        }

        t.relative = new TimelibRelTime();
        t.relative.y = -(interval.y * bias);
        t.relative.m = -(interval.m * bias);
        t.relative.d = -(interval.d * bias);
        t.relative.h = -(interval.h * bias);
        t.relative.i = -(interval.i * bias);
        t.relative.s = -(interval.s * bias);
        t.relative.us = -(interval.us * bias);
        t.haveRelative = 1;
        t.sseUptodate = 0;

        Tm2Unixtime.updateTs(t, null);
        Unixtime2Tm.updateFromSse(t);
        t.haveRelative = 0;

        return t;
    }

    private static void doRangeLimit(long start, long end, long adj, long[] a, long[] b) {
        if (a[0] < start) {
            b[0] -= (start - a[0] - 1) / adj + 1;
            a[0] += adj * ((start - a[0] - 1) / adj + 1);
        }
        if (a[0] >= end) {
            b[0] += a[0] / adj;
            a[0] -= adj * (a[0] / adj);
        }
    }

    /** C: {@code timelib_add_wall} */
    @TruffleBoundary
    public static TimelibTime addWall(TimelibTime oldTime, TimelibRelTime interval) {
        int bias = 1;
        TimelibTime t = Timelib.timeClone(oldTime);

        t.haveRelative = 1;
        t.sseUptodate = 0;

        if (interval.haveWeekdayRelative != 0 || interval.haveSpecialRelative != 0) {
            t.relative = interval.copy();
            Tm2Unixtime.updateTs(t, null);
            Unixtime2Tm.updateFromSse(t);
        } else {
            if (interval.invert != 0) {
                bias = -1;
            }
            t.relative = new TimelibRelTime();
            t.relative.y = interval.y * bias;
            t.relative.m = interval.m * bias;
            t.relative.d = interval.d * bias;

            if (t.relative.y != 0 || t.relative.m != 0 || t.relative.d != 0) {
                Tm2Unixtime.updateTs(t, null);
            }

            if (interval.us == 0) {
                t.sse += bias * Timelib.hmsToSeconds(interval.h, interval.i, interval.s);
                Unixtime2Tm.updateFromSse(t);
            } else {
                TimelibRelTime tempInterval = interval.copy();
                long[] us = new long[] { tempInterval.us };
                long[] s = new long[] { tempInterval.s };
                doRangeLimit(0, 1000000, 1000000, us, s);
                tempInterval.us = us[0];
                tempInterval.s = s[0];

                t.sse += bias * Timelib.hmsToSeconds(tempInterval.h, tempInterval.i, tempInterval.s);
                Unixtime2Tm.updateFromSse(t);
                t.us += tempInterval.us * bias;

                Tm2Unixtime.doNormalize(t);
                Tm2Unixtime.updateTs(t, null);
            }
            Tm2Unixtime.doNormalize(t);
        }

        if (t.zoneType == TIMELIB_ZONETYPE_ID && t.tzInfo != null) {
            Unixtime2Tm.setTimezone(t, t.tzInfo);
        }
        t.haveRelative = 0;

        return t;
    }

    /** C: {@code timelib_sub_wall} */
    @TruffleBoundary
    public static TimelibTime subWall(TimelibTime oldTime, TimelibRelTime interval) {
        int bias = 1;
        TimelibTime t = Timelib.timeClone(oldTime);

        t.haveRelative = 1;
        t.sseUptodate = 0;

        if (interval.haveWeekdayRelative != 0 || interval.haveSpecialRelative != 0) {
            t.relative = interval.copy();
            Tm2Unixtime.updateTs(t, null);
            Unixtime2Tm.updateFromSse(t);
        } else {
            if (interval.invert != 0) {
                bias = -1;
            }
            t.relative = new TimelibRelTime();
            t.relative.y = -(interval.y * bias);
            t.relative.m = -(interval.m * bias);
            t.relative.d = -(interval.d * bias);

            if (t.relative.y != 0 || t.relative.m != 0 || t.relative.d != 0) {
                Tm2Unixtime.updateTs(t, null);
            }

            if (interval.us == 0) {
                t.sse -= bias * Timelib.hmsToSeconds(interval.h, interval.i, interval.s);
                Unixtime2Tm.updateFromSse(t);
            } else {
                TimelibRelTime tempInterval = interval.copy();
                long[] us = new long[] { tempInterval.us };
                long[] s = new long[] { tempInterval.s };
                doRangeLimit(0, 1000000, 1000000, us, s);
                tempInterval.us = us[0];
                tempInterval.s = s[0];

                t.sse -= bias * Timelib.hmsToSeconds(tempInterval.h, tempInterval.i, tempInterval.s);
                Unixtime2Tm.updateFromSse(t);
                t.us -= tempInterval.us * bias;

                Tm2Unixtime.doNormalize(t);
                Tm2Unixtime.updateTs(t, null);
            }
            Tm2Unixtime.doNormalize(t);
        }

        if (t.zoneType == TIMELIB_ZONETYPE_ID && t.tzInfo != null) {
            Unixtime2Tm.setTimezone(t, t.tzInfo);
        }
        t.haveRelative = 0;

        return t;
    }

    /** C: {@code date_interval_format} */
    @TruffleBoundary
    public static String format(String format, TimelibRelTime t) {
        if (format == null || format.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        boolean haveFormatSpec = false;
        for (int i = 0; i < format.length(); i++) {
            char c = format.charAt(i);
            if (haveFormatSpec) {
                switch (c) {
                    case 'Y' -> sb.append(String.format(java.util.Locale.ROOT, "%02d", t.y));
                    case 'y' -> sb.append(t.y);
                    case 'M' -> sb.append(String.format(java.util.Locale.ROOT, "%02d", t.m));
                    case 'm' -> sb.append(t.m);
                    case 'D' -> sb.append(String.format(java.util.Locale.ROOT, "%02d", t.d));
                    case 'd' -> sb.append(t.d);
                    case 'H' -> sb.append(String.format(java.util.Locale.ROOT, "%02d", t.h));
                    case 'h' -> sb.append(t.h);
                    case 'I' -> sb.append(String.format(java.util.Locale.ROOT, "%02d", t.i));
                    case 'i' -> sb.append(t.i);
                    case 'S' -> sb.append(String.format(java.util.Locale.ROOT, "%02d", t.s));
                    case 's' -> sb.append(t.s);
                    case 'F' -> sb.append(String.format(java.util.Locale.ROOT, "%06d", t.us));
                    case 'f' -> sb.append(t.us);
                    case 'a' -> {
                        if (t.days != TIMELIB_UNSET) {
                            sb.append(t.days);
                        } else {
                            sb.append("(unknown)");
                        }
                    }
                    case 'r' -> sb.append(t.invert != 0 ? "-" : "");
                    case 'R' -> sb.append(t.invert != 0 ? '-' : '+');
                    case '%' -> sb.append('%');
                    default -> sb.append('%').append(c);
                }
                haveFormatSpec = false;
            } else {
                if (c == '%') {
                    haveFormatSpec = true;
                } else {
                    sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
