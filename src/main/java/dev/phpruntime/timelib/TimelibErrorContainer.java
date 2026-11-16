// Ported from timelib 2022.17 (ext/date/lib/timelib.h),
// Copyright (c) 2015-2021 Derick Rethans, Copyright (c) 2017-2019,2021 MongoDB, Inc.,
// MIT License (see src/main/resources/META-INF/LICENSE-timelib).

package dev.phpruntime.timelib;

import java.util.ArrayList;
import java.util.List;

/** Port of {@code timelib_error_container} from {@code timelib.h}. */
public final class TimelibErrorContainer {

    public final List<TimelibErrorMessage> errorMessages = new ArrayList<>();
    public final List<TimelibErrorMessage> warningMessages = new ArrayList<>();
    public int errorCount;
    public int warningCount;

    public void addError(int errorCode, int position, char character, String message) {
        if (errorCount >= 1000) { // TIMELIB_MAX_ERRORS
            return;
        }
        TimelibErrorMessage msg = new TimelibErrorMessage();
        msg.errorCode = errorCode;
        msg.position = position;
        msg.character = character;
        msg.message = message;
        errorMessages.add(msg);
        errorCount++;
    }

    public void addWarning(int errorCode, int position, char character, String message) {
        if (warningCount >= 1000) { // TIMELIB_MAX_ERRORS
            return;
        }
        TimelibErrorMessage msg = new TimelibErrorMessage();
        msg.errorCode = errorCode;
        msg.position = position;
        msg.character = character;
        msg.message = message;
        warningMessages.add(msg);
        warningCount++;
    }

    public TimelibErrorContainer copy() {
        TimelibErrorContainer c = new TimelibErrorContainer();
        for (TimelibErrorMessage m : this.errorMessages) {
            c.errorMessages.add(m.copy());
        }
        for (TimelibErrorMessage m : this.warningMessages) {
            c.warningMessages.add(m.copy());
        }
        c.errorCount = this.errorCount;
        c.warningCount = this.warningCount;
        return c;
    }
}
