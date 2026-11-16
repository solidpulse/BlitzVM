// Ported from timelib 2022.17 (ext/date/lib/timelib.h, timelib_private.h),
// Copyright (c) 2015-2021 Derick Rethans, Copyright (c) 2017-2019,2021 MongoDB, Inc.,
// MIT License (see src/main/resources/META-INF/LICENSE-timelib).

package dev.phpruntime.timelib;

/** Constants from {@code timelib.h} and {@code timelib_private.h}. */
public final class TimelibConstants {

    private TimelibConstants() {
    }

    public static final int TIMELIB_VERSION = 202217;
    public static final int TIMELIB_EXTENDED_VERSION = 20221701;
    public static final String TIMELIB_ASCII_VERSION = "2022.17";

    public static final int TIMELIB_NONE = 0x00;
    public static final int TIMELIB_OVERRIDE_TIME = 0x01;
    public static final int TIMELIB_NO_CLONE = 0x02;

    public static final int TIMELIB_UNSET = -9999999;

    public static final int TIMELIB_ZONETYPE_NONE = 0;
    public static final int TIMELIB_ZONETYPE_OFFSET = 1;
    public static final int TIMELIB_ZONETYPE_ABBR = 2;
    public static final int TIMELIB_ZONETYPE_ID = 3;

    public static final int TIMELIB_POSIX_TRANS_TYPE_JULIAN_NO_FEB29 = 1;
    public static final int TIMELIB_POSIX_TRANS_TYPE_JULIAN_FEB29 = 2;
    public static final int TIMELIB_POSIX_TRANS_TYPE_MWD = 3;

    // Warning and error codes (from timelib.h)
    public static final int TIMELIB_WARN_MASK = 0x1ff;
    public static final int TIMELIB_ERR_MASK = 0x2ff;

    public static final int TIMELIB_WARN_DOUBLE_TZ = 0x101;
    public static final int TIMELIB_WARN_INVALID_TIME = 0x102;
    public static final int TIMELIB_WARN_INVALID_DATE = 0x103;
    public static final int TIMELIB_WARN_TRAILING_DATA = 0x11a;

    public static final int TIMELIB_ERR_DOUBLE_TZ = 0x201;
    public static final int TIMELIB_ERR_TZID_NOT_FOUND = 0x202;
    public static final int TIMELIB_ERR_DOUBLE_TIME = 0x203;
    public static final int TIMELIB_ERR_DOUBLE_DATE = 0x204;
    public static final int TIMELIB_ERR_UNEXPECTED_CHARACTER = 0x205;
    public static final int TIMELIB_ERR_EMPTY_STRING = 0x206;
    public static final int TIMELIB_ERR_UNEXPECTED_DATA = 0x207;
    public static final int TIMELIB_ERR_NO_TEXTUAL_DAY = 0x208;
    public static final int TIMELIB_ERR_NO_TWO_DIGIT_DAY = 0x209;
    public static final int TIMELIB_ERR_NO_THREE_DIGIT_DAY_OF_YEAR = 0x20a;
    public static final int TIMELIB_ERR_NO_TWO_DIGIT_MONTH = 0x20b;
    public static final int TIMELIB_ERR_NO_TEXTUAL_MONTH = 0x20c;
    public static final int TIMELIB_ERR_NO_TWO_DIGIT_YEAR = 0x20d;
    public static final int TIMELIB_ERR_NO_FOUR_DIGIT_YEAR = 0x20e;
    public static final int TIMELIB_ERR_NO_TWO_DIGIT_HOUR = 0x20f;
    public static final int TIMELIB_ERR_HOUR_LARGER_THAN_12 = 0x210;
    public static final int TIMELIB_ERR_MERIDIAN_BEFORE_HOUR = 0x211;
    public static final int TIMELIB_ERR_NO_MERIDIAN = 0x212;
    public static final int TIMELIB_ERR_NO_TWO_DIGIT_MINUTE = 0x213;
    public static final int TIMELIB_ERR_NO_TWO_DIGIT_SECOND = 0x214;
    public static final int TIMELIB_ERR_NO_SIX_DIGIT_MICROSECOND = 0x215;
    public static final int TIMELIB_ERR_NO_SEP_SYMBOL = 0x216;
    public static final int TIMELIB_ERR_EXPECTED_ESCAPE_CHAR = 0x217;
    public static final int TIMELIB_ERR_NO_ESCAPED_CHAR = 0x218;
    public static final int TIMELIB_ERR_WRONG_FORMAT_SEP = 0x219;
    public static final int TIMELIB_ERR_TRAILING_DATA = 0x21a;
    public static final int TIMELIB_ERR_DATA_MISSING = 0x21b;
    public static final int TIMELIB_ERR_NO_THREE_DIGIT_MILLISECOND = 0x21c;
    public static final int TIMELIB_ERR_NO_FOUR_DIGIT_YEAR_ISO = 0x21d;
    public static final int TIMELIB_ERR_NO_TWO_DIGIT_WEEK = 0x21e;
    public static final int TIMELIB_ERR_INVALID_WEEK = 0x21f;
    public static final int TIMELIB_ERR_NO_DAY_OF_WEEK = 0x220;
    public static final int TIMELIB_ERR_INVALID_DAY_OF_WEEK = 0x221;
    public static final int TIMELIB_ERR_INVALID_SPECIFIER = 0x222;
    public static final int TIMELIB_ERR_INVALID_TZ_OFFSET = 0x223;
    public static final int TIMELIB_ERR_FORMAT_LITERAL_MISMATCH = 0x224;
    public static final int TIMELIB_ERR_MIX_ISO_WITH_NATURAL = 0x225;
    public static final int TIMELIB_ERR_NUMBER_OUT_OF_RANGE = 0x226;

    // Format specifier codes (from timelib.h)
    public static final int TIMELIB_FORMAT_ALLOW_EXTRA_CHARACTERS = 0;
    public static final int TIMELIB_FORMAT_ANY_SEPARATOR = 1;
    public static final int TIMELIB_FORMAT_DAY_TWO_DIGIT = 2;
    public static final int TIMELIB_FORMAT_DAY_TWO_DIGIT_PADDED = 3;
    public static final int TIMELIB_FORMAT_DAY_OF_WEEK_ISO = 4;
    public static final int TIMELIB_FORMAT_DAY_OF_WEEK = 5;
    public static final int TIMELIB_FORMAT_DAY_OF_YEAR = 6;
    public static final int TIMELIB_FORMAT_DAY_SUFFIX = 7;
    public static final int TIMELIB_FORMAT_END = 8;
    public static final int TIMELIB_FORMAT_EPOCH_SECONDS = 9;
    public static final int TIMELIB_FORMAT_ESCAPE = 10;
    public static final int TIMELIB_FORMAT_HOUR_TWO_DIGIT_12_MAX = 11;
    public static final int TIMELIB_FORMAT_HOUR_TWO_DIGIT_12_MAX_PADDED = 12;
    public static final int TIMELIB_FORMAT_HOUR_TWO_DIGIT_24_MAX = 13;
    public static final int TIMELIB_FORMAT_HOUR_TWO_DIGIT_24_MAX_PADDED = 14;
    public static final int TIMELIB_FORMAT_LITERAL = 15;
    public static final int TIMELIB_FORMAT_MERIDIAN = 16;
    public static final int TIMELIB_FORMAT_MICROSECOND_SIX_DIGIT = 17;
    public static final int TIMELIB_FORMAT_MILLISECOND_THREE_DIGIT = 18;
    public static final int TIMELIB_FORMAT_MINUTE_TWO_DIGIT = 19;
    public static final int TIMELIB_FORMAT_MONTH_TWO_DIGIT = 20;
    public static final int TIMELIB_FORMAT_MONTH_TWO_DIGIT_PADDED = 21;
    public static final int TIMELIB_FORMAT_RANDOM_CHAR = 22;
    public static final int TIMELIB_FORMAT_RESET_ALL = 23;
    public static final int TIMELIB_FORMAT_RESET_ALL_WHEN_NOT_SET = 24;
    public static final int TIMELIB_FORMAT_SECOND_TWO_DIGIT = 25;
    public static final int TIMELIB_FORMAT_SEPARATOR = 26;
    public static final int TIMELIB_FORMAT_SKIP_TO_SEPARATOR = 27;
    public static final int TIMELIB_FORMAT_TEXTUAL_DAY_3_LETTER = 28;
    public static final int TIMELIB_FORMAT_TEXTUAL_DAY_FULL = 29;
    public static final int TIMELIB_FORMAT_TEXTUAL_MONTH_3_LETTER = 30;
    public static final int TIMELIB_FORMAT_TEXTUAL_MONTH_FULL = 31;
    public static final int TIMELIB_FORMAT_TIMEZONE_OFFSET = 32;
    public static final int TIMELIB_FORMAT_TIMEZONE_OFFSET_MINUTES = 33;
    public static final int TIMELIB_FORMAT_WEEK_OF_YEAR_ISO = 34;
    public static final int TIMELIB_FORMAT_WEEK_OF_YEAR = 35;
    public static final int TIMELIB_FORMAT_WHITESPACE = 36;
    public static final int TIMELIB_FORMAT_YEAR_TWO_DIGIT = 37;
    public static final int TIMELIB_FORMAT_YEAR_FOUR_DIGIT = 38;
    public static final int TIMELIB_FORMAT_YEAR_EXPANDED = 39;
    public static final int TIMELIB_FORMAT_YEAR_ISO = 40;

    // Tzfile parsing error codes (from timelib.h)
    public static final int TIMELIB_ERROR_NO_ERROR = 0x00;
    public static final int TIMELIB_ERROR_CANNOT_ALLOCATE = 0x01;
    public static final int TIMELIB_ERROR_CORRUPT_TRANSITIONS_DONT_INCREASE = 0x02;
    public static final int TIMELIB_ERROR_CORRUPT_NO_64BIT_PREAMBLE = 0x03;
    public static final int TIMELIB_ERROR_CORRUPT_NO_ABBREVIATION = 0x04;
    public static final int TIMELIB_ERROR_UNSUPPORTED_VERSION = 0x05;
    public static final int TIMELIB_ERROR_NO_SUCH_TIMEZONE = 0x06;
    public static final int TIMELIB_ERROR_SLIM_FILE = 0x07;
    public static final int TIMELIB_ERROR_CORRUPT_POSIX_STRING = 0x08;
    public static final int TIMELIB_ERROR_EMPTY_POSIX_STRING = 0x09;

    // Private constants (from timelib_private.h)
    public static final int TIMELIB_SECOND = 1;
    public static final int TIMELIB_MINUTE = 2;
    public static final int TIMELIB_HOUR = 3;
    public static final int TIMELIB_DAY = 4;
    public static final int TIMELIB_MONTH = 5;
    public static final int TIMELIB_YEAR = 6;
    public static final int TIMELIB_WEEKDAY = 7;
    public static final int TIMELIB_SPECIAL = 8;
    public static final int TIMELIB_MICROSEC = 9;

    public static final int TIMELIB_SPECIAL_WEEKDAY = 0x01;
    public static final int TIMELIB_SPECIAL_DAY_OF_WEEK_IN_MONTH = 0x02;
    public static final int TIMELIB_SPECIAL_LAST_DAY_OF_WEEK_IN_MONTH = 0x03;

    public static final int TIMELIB_SPECIAL_FIRST_DAY_OF_MONTH = 0x01;
    public static final int TIMELIB_SPECIAL_LAST_DAY_OF_MONTH = 0x02;

    public static final int TIMELIB_TIME_PART_DONT_KEEP = 0x00;
    public static final int TIMELIB_TIME_PART_KEEP = 0x01;

    public static final int MINS_PER_HOUR = 60;
    public static final long SECS_PER_ERA = 12622780800L;
    public static final int SECS_PER_DAY = 86400;
    public static final int SECS_PER_HOUR = 3600;
    public static final long USECS_PER_HOUR = 3600000000L;

    public static final int DAYS_PER_WEEK = 7;
    public static final int DAYS_PER_YEAR = 365;
    public static final int DAYS_PER_LYEAR = 366;
    public static final int MONTHS_PER_YEAR = 12;
    public static final int DAYS_PER_ERA = 146097;
    public static final int YEARS_PER_ERA = 400;
    public static final int HINNANT_EPOCH_SHIFT = 719468;

    public static final int TIMELIB_TZINFO_PHP = 0x01;
    public static final int TIMELIB_TZINFO_ZONEINFO = 0x02;

    public static boolean isLeap(long y) {
        return y % 4 == 0 && (y % 100 != 0 || y % 400 == 0);
    }
}
