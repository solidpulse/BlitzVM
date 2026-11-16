// Ported from timelib 2022.17 (ext/date/lib/timelib.h),
// Copyright (c) 2015-2021 Derick Rethans, Copyright (c) 2017-2019,2021 MongoDB, Inc.,
// MIT License (see src/main/resources/META-INF/LICENSE-timelib).

package dev.phpruntime.timelib;

/** Port of {@code timelib_rel_time.special} from {@code timelib.h}. */
public final class TimelibSpecial {

    public int type;
    public long amount;

    public TimelibSpecial copy() {
        TimelibSpecial c = new TimelibSpecial();
        c.type = this.type;
        c.amount = this.amount;
        return c;
    }
}
