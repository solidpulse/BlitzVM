// Ported from timelib 2022.17 (ext/date/lib/parse_tz.c),
// Copyright (c) 2015-2021 Derick Rethans, Copyright (c) 2017-2019,2021 MongoDB, Inc.,
// MIT License (see src/main/resources/META-INF/LICENSE-timelib).

package dev.phpruntime.timelib;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static dev.phpruntime.timelib.TimelibConstants.*;

/** Port of {@code parse_tz.c} (TZif v2/v3/v4 reader and offset lookup). */
public final class ParseTz {

    private ParseTz() {
    }

    private static final class ByteCursor {
        final byte[] data;
        int pos;

        ByteCursor(byte[] data) {
            this.data = data;
        }

        int readU8() {
            return data[pos++] & 0xff;
        }

        int readI32BE() {
            int v = ((data[pos] & 0xff) << 24)
                    | ((data[pos + 1] & 0xff) << 16)
                    | ((data[pos + 2] & 0xff) << 8)
                    | (data[pos + 3] & 0xff);
            pos += 4;
            return v;
        }

        long readU32BE() {
            return Integer.toUnsignedLong(readI32BE());
        }

        long readI64BE() {
            long v = ((long) (data[pos] & 0xff) << 56)
                    | ((long) (data[pos + 1] & 0xff) << 48)
                    | ((long) (data[pos + 2] & 0xff) << 40)
                    | ((long) (data[pos + 3] & 0xff) << 32)
                    | ((long) (data[pos + 4] & 0xff) << 24)
                    | ((long) (data[pos + 5] & 0xff) << 16)
                    | ((long) (data[pos + 6] & 0xff) << 8)
                    | (long) (data[pos + 7] & 0xff);
            pos += 8;
            return v;
        }

        boolean matchAscii(String s) {
            if (pos + s.length() > data.length) {
                return false;
            }
            for (int i = 0; i < s.length(); i++) {
                if ((data[pos + i] & 0xff) != s.charAt(i)) {
                    return false;
                }
            }
            return true;
        }
    }

    /** C: {@code read_tzif_preamble} */
    private static int readTzifPreamble(ByteCursor tzf, TimelibTzinfo tz) {
        if (tzf.pos + 20 > tzf.data.length) {
            return -1;
        }
        int version;
        switch (tzf.data[tzf.pos + 4]) {
            case 0 -> version = 0;
            case '2' -> version = 2;
            case '3' -> version = 3;
            case '4' -> version = 4;
            default -> {
                return -1;
            }
        }
        tzf.pos += 5;

        tz.bc = 0;
        tz.location.countryCode[0] = '?';
        tz.location.countryCode[1] = '?';
        tz.location.countryCode[2] = '\0';

        tzf.pos += 15;
        return version;
    }

    /** C: {@code read_preamble} */
    private static int readPreamble(ByteCursor tzf, TimelibTzinfo tz, int[] type) {
        if (tzf.matchAscii("TZif")) {
            type[0] = TIMELIB_TZINFO_ZONEINFO;
            return readTzifPreamble(tzf, tz);
        }
        return -1;
    }

    /** C: {@code read_32bit_header} */
    private static void read32BitHeader(ByteCursor tzf, TimelibTzinfo tz) {
        tz.bit32.ttisgmtcnt = tzf.readU32BE();
        tz.bit32.ttisstdcnt = tzf.readU32BE();
        tz.bit32.leapcnt = tzf.readU32BE();
        tz.bit32.timecnt = tzf.readU32BE();
        tz.bit32.typecnt = tzf.readU32BE();
        tz.bit32.charcnt = tzf.readU32BE();
    }

    /** C: {@code skip_32bit_transitions} */
    private static void skip32BitTransitions(ByteCursor tzf, TimelibTzinfo tz) {
        if (tz.bit32.timecnt > 0) {
            tzf.pos += (int) (4 * tz.bit32.timecnt);
            tzf.pos += (int) tz.bit32.timecnt;
        }
    }

    /** C: {@code skip_32bit_types} */
    private static void skip32BitTypes(ByteCursor tzf, TimelibTzinfo tz) {
        tzf.pos += (int) (6 * tz.bit32.typecnt);
        tzf.pos += (int) tz.bit32.charcnt;
        if (tz.bit32.leapcnt > 0) {
            tzf.pos += (int) (8 * tz.bit32.leapcnt);
        }
        if (tz.bit32.ttisstdcnt > 0) {
            tzf.pos += (int) tz.bit32.ttisstdcnt;
        }
        if (tz.bit32.ttisgmtcnt > 0) {
            tzf.pos += (int) tz.bit32.ttisgmtcnt;
        }
    }

    /** C: {@code skip_64bit_preamble} */
    private static boolean skip64BitPreamble(ByteCursor tzf) {
        if (tzf.matchAscii("TZif2") || tzf.matchAscii("TZif3") || tzf.matchAscii("TZif4")) {
            tzf.pos += 20;
            return true;
        }
        return false;
    }

    /** C: {@code read_64bit_header} */
    private static void read64BitHeader(ByteCursor tzf, TimelibTzinfo tz) {
        tz.bit64.ttisgmtcnt = tzf.readU32BE();
        tz.bit64.ttisstdcnt = tzf.readU32BE();
        tz.bit64.leapcnt = tzf.readU32BE();
        tz.bit64.timecnt = tzf.readU32BE();
        tz.bit64.typecnt = tzf.readU32BE();
        tz.bit64.charcnt = tzf.readU32BE();
    }

    /** C: {@code read_64bit_transitions} */
    private static int read64BitTransitions(ByteCursor tzf, TimelibTzinfo tz) {
        if (tz.bit64.timecnt > 0) {
            int n = (int) tz.bit64.timecnt;
            long[] buffer = new long[n];
            for (int i = 0; i < n; i++) {
                buffer[i] = tzf.readI64BE();
                if (i > 0 && !(buffer[i] > buffer[i - 1])) {
                    return TIMELIB_ERROR_CORRUPT_TRANSITIONS_DONT_INCREASE;
                }
            }
            int[] cbuffer = new int[n];
            for (int i = 0; i < n; i++) {
                cbuffer[i] = tzf.readU8();
            }
            tz.trans = buffer;
            tz.transIdx = cbuffer;
        }
        return 0;
    }

    /** C: {@code read_64bit_types} */
    private static int read64BitTypes(ByteCursor tzf, TimelibTzinfo tz) {
        int typecnt = (int) tz.bit64.typecnt;
        // Two extra slots for potential new ttinfo entries from POSIX string
        tz.type = new TimelibTzinfo.Ttinfo[typecnt + 2];
        for (int i = 0; i < typecnt + 2; i++) {
            tz.type[i] = new TimelibTzinfo.Ttinfo();
        }

        for (int i = 0; i < typecnt; i++) {
            tz.type[i].offset = tzf.readI32BE();
            tz.type[i].isdst = tzf.readU8();
            tz.type[i].abbrIdx = tzf.readU8();
        }

        int charcnt = (int) tz.bit64.charcnt;
        tz.timezoneAbbr = Arrays.copyOfRange(tzf.data, tzf.pos, tzf.pos + charcnt);
        tzf.pos += charcnt;

        if (tz.bit64.leapcnt > 0) {
            int leapcnt = (int) tz.bit64.leapcnt;
            tz.leapTimes = new TimelibTzinfo.Tlinfo[leapcnt];
            for (int i = 0; i < leapcnt; i++) {
                TimelibTzinfo.Tlinfo tl = new TimelibTzinfo.Tlinfo();
                tl.trans = tzf.readI64BE();
                tl.offset = tzf.readI32BE();
                tz.leapTimes[i] = tl;
            }
        }

        if (tz.bit64.ttisstdcnt > 0) {
            int n = (int) tz.bit64.ttisstdcnt;
            for (int i = 0; i < n; i++) {
                tz.type[i].isstdcnt = tzf.readU8();
            }
        }

        if (tz.bit64.ttisgmtcnt > 0) {
            int n = (int) tz.bit64.ttisgmtcnt;
            for (int i = 0; i < n; i++) {
                tz.type[i].isgmtcnt = tzf.readU8();
            }
        }

        return 0;
    }

    /** C: {@code read_posix_string} */
    private static void readPosixString(ByteCursor tzf, TimelibTzinfo tz) {
        if (tzf.pos >= tzf.data.length) {
            tz.posixString = "";
            return;
        }
        // POSIX string is delimited by \n
        tzf.pos++;
        int begin = tzf.pos;
        while (tzf.pos < tzf.data.length && tzf.data[tzf.pos] != '\n') {
            tzf.pos++;
        }
        tz.posixString = new String(tzf.data, begin, tzf.pos - begin, StandardCharsets.US_ASCII);
        if (tzf.pos < tzf.data.length && tzf.data[tzf.pos] == '\n') {
            tzf.pos++;
        }
    }

    /** C: {@code find_ttinfo_index} */
    private static int findTtinfoIndex(TimelibTzinfo tz, int offset, int isdst, String abbr) {
        for (int i = 0; i < tz.bit64.typecnt; i++) {
            if (offset == tz.type[i].offset
                    && isdst == tz.type[i].isdst
                    && abbr.equals(tz.getAbbr(tz.type[i].abbrIdx))) {
                return i;
            }
        }
        return TIMELIB_UNSET;
    }

    /** C: {@code add_abbr} */
    private static int addAbbr(TimelibTzinfo tz, String abbr) {
        byte[] abbrBytes = abbr.getBytes(StandardCharsets.US_ASCII);
        int oldLength = (int) tz.bit64.charcnt;
        int newLength = oldLength + abbrBytes.length + 1;
        byte[] newArr = Arrays.copyOf(tz.timezoneAbbr, newLength);
        System.arraycopy(abbrBytes, 0, newArr, oldLength, abbrBytes.length);
        newArr[newLength - 1] = 0;
        tz.timezoneAbbr = newArr;
        tz.bit64.charcnt = newLength;
        return oldLength;
    }

    /** C: {@code add_new_ttinfo_index} */
    private static int addNewTtinfoIndex(TimelibTzinfo tz, int offset, int isdst, String abbr) {
        int idx = (int) tz.bit64.typecnt;
        tz.type[idx].offset = offset;
        tz.type[idx].isdst = isdst;
        tz.type[idx].abbrIdx = addAbbr(tz, abbr);
        tz.type[idx].isstdcnt = 0;
        tz.type[idx].isgmtcnt = 0;
        tz.bit64.typecnt++;
        return idx;
    }

    /** C: {@code integrate_posix_string} */
    private static boolean integratePosixString(TimelibTzinfo tz) {
        tz.posixInfo = ParsePosix.parsePosixStr(tz.posixString);
        if (tz.posixInfo == null) {
            return false;
        }

        tz.posixInfo.typeIndexStdType = findTtinfoIndex(tz, (int) tz.posixInfo.stdOffset, 0, tz.posixInfo.std);
        if (tz.posixInfo.typeIndexStdType == TIMELIB_UNSET) {
            tz.posixInfo.typeIndexStdType = addNewTtinfoIndex(tz, (int) tz.posixInfo.stdOffset, 0, tz.posixInfo.std);
            return true;
        }

        if (tz.posixInfo.dst == null) {
            return true;
        }

        tz.posixInfo.typeIndexDstType = findTtinfoIndex(tz, (int) tz.posixInfo.dstOffset, 1, tz.posixInfo.dst);
        if (tz.posixInfo.typeIndexDstType == TIMELIB_UNSET) {
            tz.posixInfo.typeIndexDstType = addNewTtinfoIndex(tz, (int) tz.posixInfo.dstOffset, 1, tz.posixInfo.dst);
            return true;
        }

        return true;
    }

    /** C: {@code set_default_location_and_comments} */
    private static void setDefaultLocationAndComments(TimelibTzinfo tz) {
        tz.location.latitude = 0;
        tz.location.longitude = 0;
        tz.location.comments = "?";
    }

    /** C: {@code timelib_parse_tzfile} (from TZif byte array) */
    public static TimelibTzinfo parseTzfileBytes(String canonicalName, byte[] data, int[] errorCode) {
        errorCode[0] = TIMELIB_ERROR_NO_ERROR;
        if (data == null || data.length < 44) {
            errorCode[0] = TIMELIB_ERROR_NO_SUCH_TIMEZONE;
            return null;
        }

        ByteCursor tzf = new ByteCursor(data);
        TimelibTzinfo tmp = new TimelibTzinfo();
        tmp.name = canonicalName;
        int[] type = new int[]{TIMELIB_TZINFO_ZONEINFO};

        int version = readPreamble(tzf, tmp, type);
        if (version < 2 || version > 4) {
            errorCode[0] = TIMELIB_ERROR_UNSUPPORTED_VERSION;
            return null;
        }

        read32BitHeader(tzf, tmp);
        skip32BitTransitions(tzf, tmp);
        skip32BitTypes(tzf, tmp);

        if (!skip64BitPreamble(tzf)) {
            errorCode[0] = TIMELIB_ERROR_CORRUPT_NO_64BIT_PREAMBLE;
            return null;
        }
        read64BitHeader(tzf, tmp);

        int transitionsResult = read64BitTransitions(tzf, tmp);
        if (transitionsResult != 0) {
            errorCode[0] = transitionsResult;
            return null;
        }

        int typesResult = read64BitTypes(tzf, tmp);
        if (typesResult != 0) {
            errorCode[0] = typesResult;
            return null;
        }

        readPosixString(tzf, tmp);
        if (tmp.posixString.isEmpty()) {
            errorCode[0] = TIMELIB_ERROR_EMPTY_POSIX_STRING;
        } else if (!integratePosixString(tmp)) {
            errorCode[0] = TIMELIB_ERROR_CORRUPT_POSIX_STRING;
            return null;
        }

        setDefaultLocationAndComments(tmp);
        return tmp;
    }

    /** C: {@code timelib_fetch_timezone_offset} */
    public static TimelibTzinfo.Ttinfo fetchTimezoneOffset(TimelibTzinfo tz, long ts, long[] transitionTime) {
        if (tz.bit64.timecnt == 0 || tz.trans == null) {
            if (tz.posixInfo != null) {
                transitionTime[0] = Long.MIN_VALUE;
                return ParsePosix.fetchPosixTimezoneOffset(tz, ts, null);
            }
            if (tz.bit64.typecnt == 1) {
                transitionTime[0] = Long.MIN_VALUE;
                return tz.type[0];
            }
            return null;
        }

        if (ts < tz.trans[0]) {
            transitionTime[0] = Long.MIN_VALUE;
            return tz.type[0];
        }

        int lastIdx = (int) (tz.bit64.timecnt - 1);
        if (ts >= tz.trans[lastIdx]) {
            if (tz.posixInfo != null) {
                return ParsePosix.fetchPosixTimezoneOffset(tz, ts, transitionTime);
            }
            transitionTime[0] = tz.trans[lastIdx];
            return tz.type[tz.transIdx[lastIdx]];
        }

        int left = 0;
        int right = lastIdx;
        while (right - left > 1) {
            int mid = (left + right) >>> 1;
            if (ts < tz.trans[mid]) {
                right = mid;
            } else {
                left = mid;
            }
        }
        transitionTime[0] = tz.trans[left];
        return tz.type[tz.transIdx[left]];
    }

    /** C: {@code fetch_leaptime_offset} */
    private static TimelibTzinfo.Tlinfo fetchLeaptimeOffset(TimelibTzinfo tz, long ts) {
        if (tz.bit64.leapcnt == 0 || tz.leapTimes == null) {
            return null;
        }
        for (int i = (int) tz.bit64.leapcnt - 1; i > 0; i--) {
            if (ts > tz.leapTimes[i].trans) {
                return tz.leapTimes[i];
            }
        }
        return null;
    }

    /** C: {@code timelib_timestamp_is_in_dst} */
    public static int timestampIsInDst(long ts, TimelibTzinfo tz) {
        long[] dummy = new long[1];
        TimelibTzinfo.Ttinfo to = fetchTimezoneOffset(tz, ts, dummy);
        return to != null ? to.isdst : -1;
    }

    /** C: {@code timelib_get_time_zone_info} */
    public static TimelibTzinfo.TimelibTimeOffset getTimeZoneInfo(long ts, TimelibTzinfo tz) {
        TimelibTzinfo.TimelibTimeOffset tmp = new TimelibTzinfo.TimelibTimeOffset();
        long[] transitionTime = new long[1];
        TimelibTzinfo.Ttinfo to = fetchTimezoneOffset(tz, ts, transitionTime);

        int offset;
        String abbr;
        if (to != null) {
            offset = to.offset;
            abbr = tz.getAbbr(to.abbrIdx);
            tmp.isDst = to.isdst;
            tmp.transitionTime = transitionTime[0];
        } else {
            offset = 0;
            abbr = tz.getAbbr(0);
            tmp.isDst = 0;
            tmp.transitionTime = 0;
        }

        TimelibTzinfo.Tlinfo tl = fetchLeaptimeOffset(tz, ts);
        int leapSecs = tl != null ? -tl.offset : 0;

        tmp.offset = offset;
        tmp.leapSecs = leapSecs;
        tmp.abbr = abbr != null ? abbr : "GMT";
        return tmp;
    }

    /** C: {@code timelib_get_time_zone_offset_info} */
    public static boolean getTimeZoneOffsetInfo(long ts, TimelibTzinfo tz, int[] offset, long[] transitionTime, int[] isDst) {
        if (tz == null) {
            return false;
        }
        long[] tmpTransitionTime = new long[1];
        TimelibTzinfo.Ttinfo to = fetchTimezoneOffset(tz, ts, tmpTransitionTime);
        if (to != null) {
            if (offset != null) {
                offset[0] = to.offset;
            }
            if (isDst != null) {
                isDst[0] = to.isdst;
            }
            if (transitionTime != null) {
                transitionTime[0] = tmpTransitionTime[0];
            }
            return true;
        }
        return false;
    }

    /** C: {@code timelib_get_current_offset} */
    public static long getCurrentOffset(TimelibTime t) {
        switch (t.zoneType) {
            case TIMELIB_ZONETYPE_ABBR:
            case TIMELIB_ZONETYPE_OFFSET:
                return (long) t.z + ((long) t.dst * 3600L);
            case TIMELIB_ZONETYPE_ID: {
                int[] offset = new int[1];
                getTimeZoneOffsetInfo(t.sse, t.tzInfo, offset, null, null);
                return offset[0];
            }
            default:
                return 0;
        }
    }

    /** C: {@code timelib_same_timezone} */
    public static boolean sameTimezone(TimelibTime one, TimelibTime two) {
        if (one.zoneType != two.zoneType) {
            return false;
        }
        if (one.zoneType == TIMELIB_ZONETYPE_ABBR || one.zoneType == TIMELIB_ZONETYPE_OFFSET) {
            return (one.z + (one.dst * 3600)) == (two.z + (two.dst * 3600));
        }
        if (one.zoneType == TIMELIB_ZONETYPE_ID && one.tzInfo != null && two.tzInfo != null
                && one.tzInfo.name.equals(two.tzInfo.name)) {
            return true;
        }
        return false;
    }
}
