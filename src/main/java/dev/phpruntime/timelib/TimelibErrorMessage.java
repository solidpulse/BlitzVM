// Ported from timelib 2022.17 (ext/date/lib/timelib.h),
// Copyright (c) 2015-2021 Derick Rethans, Copyright (c) 2017-2019,2021 MongoDB, Inc.,
// MIT License (see src/main/resources/META-INF/LICENSE-timelib).

package dev.phpruntime.timelib;

/** Port of {@code timelib_error_message} from {@code timelib.h}. */
public final class TimelibErrorMessage {

    public int errorCode;
    public int position;
    public char character;
    public String message;

    public TimelibErrorMessage copy() {
        TimelibErrorMessage c = new TimelibErrorMessage();
        c.errorCode = this.errorCode;
        c.position = this.position;
        c.character = this.character;
        c.message = this.message;
        return c;
    }
}
