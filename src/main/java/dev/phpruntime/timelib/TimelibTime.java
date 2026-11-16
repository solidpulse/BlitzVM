// Ported from timelib 2022.17 (ext/date/lib/timelib.h),
// Copyright (c) 2015-2021 Derick Rethans, Copyright (c) 2017-2019,2021 MongoDB, Inc.,
// MIT License (see src/main/resources/META-INF/LICENSE-timelib).

package dev.phpruntime.timelib;

/** Port of {@code timelib_time} from {@code timelib.h}. */
public final class TimelibTime {

    public long y;
    public long m;
    public long d;
    public long h;
    public long i;
    public long s;
    public long us;
    public int z;
    public String tzAbbr;
    public TimelibTzinfo tzInfo;
    public int dst;
    public TimelibRelTime relative = new TimelibRelTime();

    public long sse;

    public int haveTime;
    public int haveDate;
    public int haveZone;
    public int haveRelative;
    public int haveWeeknrDay;

    public int sseUptodate;
    public int timUptodate;
    public int isLocaltime;
    public int zoneType;

    public TimelibTime copy() {
        TimelibTime c = new TimelibTime();
        c.y = this.y;
        c.m = this.m;
        c.d = this.d;
        c.h = this.h;
        c.i = this.i;
        c.s = this.s;
        c.us = this.us;
        c.z = this.z;
        c.tzAbbr = this.tzAbbr;
        c.tzInfo = this.tzInfo;
        c.dst = this.dst;
        c.relative = this.relative != null ? this.relative.copy() : new TimelibRelTime();
        c.sse = this.sse;
        c.haveTime = this.haveTime;
        c.haveDate = this.haveDate;
        c.haveZone = this.haveZone;
        c.haveRelative = this.haveRelative;
        c.haveWeeknrDay = this.haveWeeknrDay;
        c.sseUptodate = this.sseUptodate;
        c.timUptodate = this.timUptodate;
        c.isLocaltime = this.isLocaltime;
        c.zoneType = this.zoneType;
        return c;
    }
}
