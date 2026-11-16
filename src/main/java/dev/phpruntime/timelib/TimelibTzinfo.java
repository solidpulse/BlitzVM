// Ported from timelib 2022.17 (ext/date/lib/timelib.h, timelib_private.h),
// Copyright (c) 2015-2021 Derick Rethans, Copyright (c) 2017-2019,2021 MongoDB, Inc.,
// MIT License (see src/main/resources/META-INF/LICENSE-timelib).

package dev.phpruntime.timelib;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Port of {@code timelib_tzinfo} and its supporting structs from {@code timelib.h} and {@code timelib_private.h}. */
public final class TimelibTzinfo {

    public static final class Ttinfo {
        public int offset;
        public int isdst;
        public int abbrIdx;
        public int isstdcnt;
        public int isgmtcnt;

        public Ttinfo copy() {
            Ttinfo c = new Ttinfo();
            c.offset = this.offset;
            c.isdst = this.isdst;
            c.abbrIdx = this.abbrIdx;
            c.isstdcnt = this.isstdcnt;
            c.isgmtcnt = this.isgmtcnt;
            return c;
        }
    }

    public static final class Tlinfo {
        public long trans;
        public int offset;

        public Tlinfo copy() {
            Tlinfo c = new Tlinfo();
            c.trans = this.trans;
            c.offset = this.offset;
            return c;
        }
    }

    public static final class Tlocinfo {
        public final char[] countryCode = new char[3];
        public double latitude;
        public double longitude;
        public String comments;

        public String countryCodeString() {
            int len = 0;
            while (len < countryCode.length && countryCode[len] != '\0') {
                len++;
            }
            return new String(countryCode, 0, len);
        }

        public Tlocinfo copy() {
            Tlocinfo c = new Tlocinfo();
            System.arraycopy(this.countryCode, 0, c.countryCode, 0, 3);
            c.latitude = this.latitude;
            c.longitude = this.longitude;
            c.comments = this.comments;
            return c;
        }
    }

    public static final class PosixTransInfo {
        public int type;
        public int days;
        public int month;
        public int week;
        public int dow;
        public int hour;

        public PosixTransInfo copy() {
            PosixTransInfo c = new PosixTransInfo();
            c.type = this.type;
            c.days = this.days;
            c.month = this.month;
            c.week = this.week;
            c.dow = this.dow;
            c.hour = this.hour;
            return c;
        }
    }

    public static final class PosixStr {
        public String std;
        public long stdOffset;
        public String dst;
        public long dstOffset;
        public PosixTransInfo dstBegin;
        public PosixTransInfo dstEnd;
        public int typeIndexStdType;
        public int typeIndexDstType;

        public PosixStr copy() {
            PosixStr c = new PosixStr();
            c.std = this.std;
            c.stdOffset = this.stdOffset;
            c.dst = this.dst;
            c.dstOffset = this.dstOffset;
            c.dstBegin = this.dstBegin != null ? this.dstBegin.copy() : null;
            c.dstEnd = this.dstEnd != null ? this.dstEnd.copy() : null;
            c.typeIndexStdType = this.typeIndexStdType;
            c.typeIndexDstType = this.typeIndexDstType;
            return c;
        }
    }

    public static final class PosixTransitions {
        public int count;
        public final long[] times = new long[6];
        public final long[] types = new long[6];
    }

    public static final class TimelibTimeOffset {
        public int offset;
        public int leapSecs;
        public int isDst;
        public String abbr;
        public long transitionTime;
    }

    public static final class HeaderCounts {
        public long ttisgmtcnt;
        public long ttisstdcnt;
        public long leapcnt;
        public long timecnt;
        public long typecnt;
        public long charcnt;
    }

    public String name;
    public final HeaderCounts bit32 = new HeaderCounts();
    public final HeaderCounts bit64 = new HeaderCounts();

    public long[] trans;
    public int[] transIdx;

    public Ttinfo[] type;
    public byte[] timezoneAbbr;

    public Tlinfo[] leapTimes;
    public int bc;
    public Tlocinfo location = new Tlocinfo();

    public String posixString;
    public PosixStr posixInfo;

    public String getAbbr(int idx) {
        if (timezoneAbbr == null || idx < 0 || idx >= timezoneAbbr.length) {
            return null;
        }
        int end = idx;
        while (end < timezoneAbbr.length && timezoneAbbr[end] != 0) {
            end++;
        }
        return new String(timezoneAbbr, idx, end - idx, StandardCharsets.US_ASCII);
    }

    public TimelibTzinfo copy() {
        TimelibTzinfo c = new TimelibTzinfo();
        c.name = this.name;
        c.bit32.ttisgmtcnt = this.bit32.ttisgmtcnt;
        c.bit32.ttisstdcnt = this.bit32.ttisstdcnt;
        c.bit32.leapcnt = this.bit32.leapcnt;
        c.bit32.timecnt = this.bit32.timecnt;
        c.bit32.typecnt = this.bit32.typecnt;
        c.bit32.charcnt = this.bit32.charcnt;

        c.bit64.ttisgmtcnt = this.bit64.ttisgmtcnt;
        c.bit64.ttisstdcnt = this.bit64.ttisstdcnt;
        c.bit64.leapcnt = this.bit64.leapcnt;
        c.bit64.timecnt = this.bit64.timecnt;
        c.bit64.typecnt = this.bit64.typecnt;
        c.bit64.charcnt = this.bit64.charcnt;

        if (this.trans != null) {
            c.trans = Arrays.copyOf(this.trans, this.trans.length);
        }
        if (this.transIdx != null) {
            c.transIdx = Arrays.copyOf(this.transIdx, this.transIdx.length);
        }
        if (this.type != null) {
            c.type = new Ttinfo[this.type.length];
            for (int i = 0; i < this.type.length; i++) {
                c.type[i] = this.type[i] != null ? this.type[i].copy() : null;
            }
        }
        if (this.timezoneAbbr != null) {
            c.timezoneAbbr = Arrays.copyOf(this.timezoneAbbr, this.timezoneAbbr.length);
        }
        if (this.leapTimes != null) {
            c.leapTimes = new Tlinfo[this.leapTimes.length];
            for (int i = 0; i < this.leapTimes.length; i++) {
                c.leapTimes[i] = this.leapTimes[i] != null ? this.leapTimes[i].copy() : null;
            }
        }
        c.bc = this.bc;
        c.location = this.location != null ? this.location.copy() : new Tlocinfo();
        c.posixString = this.posixString;
        c.posixInfo = this.posixInfo != null ? this.posixInfo.copy() : null;
        return c;
    }
}
