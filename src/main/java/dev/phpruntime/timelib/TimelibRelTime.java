// Ported from timelib 2022.17 (ext/date/lib/timelib.h),
// Copyright (c) 2015-2021 Derick Rethans, Copyright (c) 2017-2019,2021 MongoDB, Inc.,
// MIT License (see src/main/resources/META-INF/LICENSE-timelib).

package dev.phpruntime.timelib;

/** Port of {@code timelib_rel_time} from {@code timelib.h}. */
public final class TimelibRelTime {

    public long y;
    public long m;
    public long d;
    public long h;
    public long i;
    public long s;
    public long us;

    public int weekday;
    public int weekdayBehavior;

    public int firstLastDayOf;
    public int invert;
    public long days;

    public TimelibSpecial special = new TimelibSpecial();

    public int haveWeekdayRelative;
    public int haveSpecialRelative;

    public TimelibRelTime copy() {
        TimelibRelTime c = new TimelibRelTime();
        c.y = this.y;
        c.m = this.m;
        c.d = this.d;
        c.h = this.h;
        c.i = this.i;
        c.s = this.s;
        c.us = this.us;
        c.weekday = this.weekday;
        c.weekdayBehavior = this.weekdayBehavior;
        c.firstLastDayOf = this.firstLastDayOf;
        c.invert = this.invert;
        c.days = this.days;
        c.special = this.special != null ? this.special.copy() : new TimelibSpecial();
        c.haveWeekdayRelative = this.haveWeekdayRelative;
        c.haveSpecialRelative = this.haveSpecialRelative;
        return c;
    }
}
