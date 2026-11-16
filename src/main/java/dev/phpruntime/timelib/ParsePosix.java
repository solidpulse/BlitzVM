// Ported from timelib 2022.17 (ext/date/lib/parse_posix.c),
// Copyright (c) 2015-2021 Derick Rethans, Copyright (c) 2017-2019,2021 MongoDB, Inc.,
// MIT License (see src/main/resources/META-INF/LICENSE-timelib).

package dev.phpruntime.timelib;

import static dev.phpruntime.timelib.TimelibConstants.*;

/** Port of {@code parse_posix.c}. */
public final class ParsePosix {

    private ParsePosix() {
    }

    private static final class Cursor {
        final String s;
        int pos;

        Cursor(String s) {
            this.s = s;
        }

        char ch() {
            return pos < s.length() ? s.charAt(pos) : '\0';
        }
    }

    private static final int[][] MONTH_LENGTHS = {
            { 31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31 },
            { 31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31 }
    };

    /** C: {@code read_description_numeric_abbr} */
    private static String readDescriptionNumericAbbr(Cursor ptr) {
        int begin = ptr.pos + 1;
        ptr.pos++; // skip '<'
        while (ptr.ch() != '\0' && ptr.ch() != '>') {
            ptr.pos++;
        }
        if (ptr.ch() == '\0') {
            return null;
        }
        if (ptr.ch() == '>') {
            ptr.pos++;
        }
        if (ptr.pos - begin - 1 < 1) {
            return null;
        }
        return ptr.s.substring(begin, ptr.pos - 1);
    }

    /** C: {@code read_description_abbr} */
    private static String readDescriptionAbbr(Cursor ptr) {
        int begin = ptr.pos;
        while ((ptr.ch() >= 'A' && ptr.ch() <= 'Z') || (ptr.ch() >= 'a' && ptr.ch() <= 'z')) {
            ptr.pos++;
        }
        if (ptr.pos - begin < 1) {
            return null;
        }
        return ptr.s.substring(begin, ptr.pos);
    }

    /** C: {@code read_description} */
    private static String readDescription(Cursor ptr) {
        if (ptr.ch() == '<') {
            return readDescriptionNumericAbbr(ptr);
        } else {
            return readDescriptionAbbr(ptr);
        }
    }

    /** C: {@code read_sign} */
    private static int readSign(Cursor ptr) {
        int bias = 1;
        if (ptr.ch() == '+') {
            ptr.pos++;
        } else if (ptr.ch() == '-') {
            bias = -1;
            ptr.pos++;
        }
        return bias;
    }

    /** C: {@code read_number} */
    private static long readNumber(Cursor ptr) {
        int begin = ptr.pos;
        int acc = 0;

        while (ptr.ch() == '0') {
            ptr.pos++;
        }
        while (ptr.ch() >= '0' && ptr.ch() <= '9') {
            acc = acc * 10;
            acc += ptr.ch() - '0';
            ptr.pos++;
        }
        if (begin == ptr.pos) {
            return TIMELIB_UNSET;
        }
        return acc;
    }

    /** C: {@code read_offset} */
    private static long readOffset(Cursor ptr) {
        int bias = readSign(ptr);
        int hours = 0;
        int minutes = 0;
        int seconds = 0;
        int begin = ptr.pos;

        long h = readNumber(ptr);
        if (h == TIMELIB_UNSET) {
            return h;
        }
        hours = (int) h;

        if (ptr.ch() == ':') {
            ptr.pos++;
            long m = readNumber(ptr);
            if (m == TIMELIB_UNSET) {
                return m;
            }
            minutes = (int) m;
        }

        if (ptr.ch() == ':') {
            ptr.pos++;
            long sec = readNumber(ptr);
            if (sec == TIMELIB_UNSET) {
                return sec;
            }
            seconds = (int) sec;
        }

        if (begin == ptr.pos) {
            return TIMELIB_UNSET;
        }

        return -1L * bias * (hours * 3600L + minutes * 60L + seconds);
    }

    /** C: {@code timelib_posix_trans_info_ctor} */
    private static TimelibTzinfo.PosixTransInfo posixTransInfoCtor() {
        TimelibTzinfo.PosixTransInfo tmp = new TimelibTzinfo.PosixTransInfo();
        tmp.type = TIMELIB_POSIX_TRANS_TYPE_JULIAN_FEB29;
        tmp.hour = 2 * 3600;
        return tmp;
    }

    /** C: {@code read_trans_spec_mwd} */
    private static TimelibTzinfo.PosixTransInfo readTransSpecMwd(Cursor ptr) {
        TimelibTzinfo.PosixTransInfo tmp = posixTransInfoCtor();
        tmp.type = TIMELIB_POSIX_TRANS_TYPE_MWD;
        ptr.pos++; // skip 'M'

        long m = readNumber(ptr);
        if (m == TIMELIB_UNSET) {
            return null;
        }
        tmp.month = (int) m;

        if (ptr.ch() != '.') {
            return null;
        }
        ptr.pos++;

        long w = readNumber(ptr);
        if (w == TIMELIB_UNSET) {
            return null;
        }
        tmp.week = (int) w;

        if (ptr.ch() != '.') {
            return null;
        }
        ptr.pos++;

        long d = readNumber(ptr);
        if (d == TIMELIB_UNSET) {
            return null;
        }
        tmp.dow = (int) d;

        return tmp;
    }

    /** C: {@code read_transition_spec} */
    private static TimelibTzinfo.PosixTransInfo readTransitionSpec(Cursor ptr) {
        TimelibTzinfo.PosixTransInfo tmp;
        if (ptr.ch() == 'M') {
            tmp = readTransSpecMwd(ptr);
            if (tmp == null) {
                return null;
            }
        } else {
            tmp = posixTransInfoCtor();
            if (ptr.ch() == 'J') {
                tmp.type = TIMELIB_POSIX_TRANS_TYPE_JULIAN_NO_FEB29;
                ptr.pos++;
            }
            long days = readNumber(ptr);
            if (days == TIMELIB_UNSET) {
                return null;
            }
            tmp.days = (int) days;
        }

        if (ptr.ch() == '/') {
            ptr.pos++;
            long h = readOffset(ptr);
            if (h == TIMELIB_UNSET) {
                return null;
            }
            tmp.hour = (int) (-h);
        }

        return tmp;
    }

    /** C: {@code timelib_parse_posix_str} */
    public static TimelibTzinfo.PosixStr parsePosixStr(String posix) {
        if (posix == null) {
            return null;
        }
        TimelibTzinfo.PosixStr tmp = new TimelibTzinfo.PosixStr();
        Cursor ptr = new Cursor(posix);

        tmp.std = readDescription(ptr);
        if (tmp.std == null) {
            return null;
        }

        tmp.stdOffset = readOffset(ptr);
        if (tmp.stdOffset == TIMELIB_UNSET) {
            return null;
        }

        if (ptr.ch() == '\0') {
            return tmp;
        }

        tmp.dstOffset = tmp.stdOffset + 3600;
        tmp.dst = readDescription(ptr);
        if (tmp.dst == null) {
            return null;
        }

        if (ptr.ch() != ',' && ptr.ch() != '\0') {
            tmp.dstOffset = readOffset(ptr);
            if (tmp.dstOffset == TIMELIB_UNSET) {
                return null;
            }
        }

        if (ptr.ch() != ',') {
            return null;
        }
        ptr.pos++; // skip ','

        tmp.dstBegin = readTransitionSpec(ptr);
        if (tmp.dstBegin == null) {
            return null;
        }

        if (ptr.ch() != ',') {
            return null;
        }
        ptr.pos++; // skip ','

        tmp.dstEnd = readTransitionSpec(ptr);
        if (tmp.dstEnd == null) {
            return null;
        }

        if (ptr.ch() != '\0') {
            return null;
        }

        return tmp;
    }

    /** C: {@code calc_transition} */
    private static long calcTransition(TimelibTzinfo.PosixTransInfo psi, long year) {
        int leapYear = isLeap(year) ? 1 : 0;

        switch (psi.type) {
            case TIMELIB_POSIX_TRANS_TYPE_JULIAN_NO_FEB29: {
                long value = psi.days - 1;
                if (leapYear != 0 && psi.days >= 60) {
                    value++;
                }
                return value * SECS_PER_DAY;
            }
            case TIMELIB_POSIX_TRANS_TYPE_JULIAN_FEB29: {
                return (long) psi.days * SECS_PER_DAY;
            }
            case TIMELIB_POSIX_TRANS_TYPE_MWD: {
                int i, d, m1, dow;
                long yy0, yy1, yy2;
                long value = 0;

                m1 = (psi.month + 9) % 12 + 1;
                yy0 = (psi.month <= 2) ? (year - 1) : year;
                yy1 = yy0 / 100;
                yy2 = yy0 % 100;
                dow = (int) (((26 * m1 - 2) / 10 + 1 + yy2 + yy2 / 4 + yy1 / 4 - 2 * yy1) % 7);
                if (dow < 0) {
                    dow += DAYS_PER_WEEK;
                }

                d = psi.dow - dow;
                if (d < 0) {
                    d += DAYS_PER_WEEK;
                }
                for (i = 1; i < psi.week; ++i) {
                    if (d + DAYS_PER_WEEK >= MONTH_LENGTHS[leapYear][psi.month - 1]) {
                        break;
                    }
                    d += DAYS_PER_WEEK;
                }

                value = (long) d * SECS_PER_DAY;
                for (i = 0; i < psi.month - 1; ++i) {
                    value += (long) MONTH_LENGTHS[leapYear][i] * SECS_PER_DAY;
                }
                return value;
            }
            default:
                return 0;
        }
    }

    /** C: {@code count_leap_years} */
    private static long countLeapYears(long y) {
        y--;
        return (y / 4) - (y / 100) + (y / 400);
    }

    /** C: {@code timelib_ts_at_start_of_year} */
    public static long tsAtStartOfYear(long year) {
        long epochLeapYears = countLeapYears(1970);
        long currentLeapYears = countLeapYears(year);
        return SECS_PER_DAY * (((year - 1970) * DAYS_PER_YEAR) + currentLeapYears - epochLeapYears);
    }

    /** C: {@code timelib_get_transitions_for_year} */
    public static void getTransitionsForYear(TimelibTzinfo tz, long year, TimelibTzinfo.PosixTransitions transitions) {
        long yearBeginTs = tsAtStartOfYear(year);

        long transBegin = yearBeginTs;
        transBegin += calcTransition(tz.posixInfo.dstBegin, year);
        transBegin += tz.posixInfo.dstBegin.hour;
        transBegin -= tz.posixInfo.stdOffset;

        long transEnd = yearBeginTs;
        transEnd += calcTransition(tz.posixInfo.dstEnd, year);
        transEnd += tz.posixInfo.dstEnd.hour;
        transEnd -= tz.posixInfo.dstOffset;

        if (transBegin < transEnd) {
            transitions.times[transitions.count] = transBegin;
            transitions.times[transitions.count + 1] = transEnd;
            transitions.types[transitions.count] = tz.posixInfo.typeIndexDstType;
            transitions.types[transitions.count + 1] = tz.posixInfo.typeIndexStdType;
        } else {
            transitions.times[transitions.count + 1] = transBegin;
            transitions.times[transitions.count] = transEnd;
            transitions.types[transitions.count + 1] = tz.posixInfo.typeIndexDstType;
            transitions.types[transitions.count] = tz.posixInfo.typeIndexStdType;
        }
        transitions.count += 2;
    }

    /** C: {@code timelib_fetch_posix_timezone_offset} */
    public static TimelibTzinfo.Ttinfo fetchPosixTimezoneOffset(TimelibTzinfo tz, long ts, long[] transitionTime) {
        if (tz.posixInfo.dstEnd == null) {
            if (transitionTime != null) {
                transitionTime[0] = tz.trans[(int) (tz.bit64.timecnt - 1)];
            }
            return tz.type[tz.posixInfo.typeIndexStdType];
        }

        TimelibTime dummy = new TimelibTime();
        Unixtime2Tm.unixtime2gmt(dummy, ts);
        long year = dummy.y;

        TimelibTzinfo.PosixTransitions transitions = new TimelibTzinfo.PosixTransitions();
        getTransitionsForYear(tz, year - 1, transitions);
        getTransitionsForYear(tz, year, transitions);
        getTransitionsForYear(tz, year + 1, transitions);

        for (int i = 1; i < transitions.count; i++) {
            if (ts < transitions.times[i]) {
                if (transitionTime != null) {
                    transitionTime[0] = transitions.times[i - 1];
                }
                return tz.type[(int) transitions.types[i - 1]];
            }
        }
        return null;
    }
}
