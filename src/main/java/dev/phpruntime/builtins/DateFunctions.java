package dev.phpruntime.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import dev.phpruntime.PhpContext;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpClass;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.timelib.Dow;
import dev.phpruntime.timelib.ParseDate;
import dev.phpruntime.timelib.ParseTz;
import dev.phpruntime.timelib.Timelib;
import dev.phpruntime.timelib.TimelibConstants;
import dev.phpruntime.timelib.TimelibErrorContainer;
import dev.phpruntime.timelib.TimelibErrorMessage;
import dev.phpruntime.timelib.TimelibTime;
import dev.phpruntime.timelib.TimelibTzinfo;
import dev.phpruntime.timelib.Tm2Unixtime;
import dev.phpruntime.timelib.Unixtime2Tm;
import dev.phpruntime.timelib.ZoneinfoDb;

import java.util.Locale;

import static dev.phpruntime.timelib.TimelibConstants.*;

/** date, gmdate, mktime, gmmktime, idate, getdate, localtime, checkdate, strtotime, and timezone functions. */
final class DateFunctions {

    static final java.time.ZoneId UTC = java.time.ZoneId.of("UTC");

    private static final String[] MON_FULL_NAMES = {
        "January", "February", "March", "April",
        "May", "June", "July", "August",
        "September", "October", "November", "December"
    };

    private static final String[] MON_SHORT_NAMES = {
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    };

    private static final String[] DAY_FULL_NAMES = {
        "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"
    };

    private static final String[] DAY_SHORT_NAMES = {
        "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"
    };

    private DateFunctions() {
    }

    static void register() {
        Builtins.def("date", 1, 2, (ctx, a) -> {
            long ts = timestamp(a, 1);
            TimelibTzinfo tzi = getTimezoneInfo(ctx);
            TimelibTime t = new TimelibTime();
            t.tzInfo = tzi;
            t.zoneType = TIMELIB_ZONETYPE_ID;
            Unixtime2Tm.unixtime2local(t, ts);
            return dateFormat(PhpOps.toStr(a[0]), t, true);
        });

        Builtins.def("gmdate", 1, 2, (ctx, a) -> {
            long ts = timestamp(a, 1);
            TimelibTime t = new TimelibTime();
            Unixtime2Tm.unixtime2gmt(t, ts);
            return dateFormat(PhpOps.toStr(a[0]), t, false);
        });

        Builtins.def("idate", 1, 2, (ctx, a) -> idate(ctx, a));
        Builtins.def("getdate", 0, 1, (ctx, a) -> getdate(ctx, a));
        Builtins.def("localtime", 0, 2, (ctx, a) -> localtime(ctx, a));

        Builtins.def("mktime", 1, 6, (ctx, a) -> phpMktime(ctx, a, false));
        Builtins.def("gmmktime", 1, 6, (ctx, a) -> phpMktime(ctx, a, true));

        Builtins.def("checkdate", 3, 3, (ctx, a) -> {
            long m = PhpOps.toLong(a[0]);
            long d = PhpOps.toLong(a[1]);
            long y = PhpOps.toLong(a[2]);
            if (y < 1 || y > 32767 || Dow.validDate(y, m, d) == 0) {
                return false;
            }
            return true;
        });

        Builtins.def("date_default_timezone_set", 1, 1, (ctx, a) -> {
            String name = PhpOps.toStr(a[0]);
            if (!ZoneinfoDb.instance().isValid(name)) {
                ctx.warning("date_default_timezone_set(): Timezone ID '" + name + "' is invalid", 0);
                return false;
            }
            ContextState st = ContextState.of(ctx);
            st.timezoneName = name;
            return true;
        });

        Builtins.def("date_default_timezone_get", 0, 0, (ctx, a) -> ContextState.of(ctx).timezoneName);
        Builtins.def("strtotime", 1, 2, (ctx, a) -> strtotime(ctx, a));
        Builtins.def("date_parse", 1, 1, (ctx, a) -> dateParse(PhpOps.toStr(a[0])));
        Builtins.def("date_parse_from_format", 2, 2, (ctx, a) -> dateParseFromFormat(PhpOps.toStr(a[0]), PhpOps.toStr(a[1])));

        Builtins.def("date_interval_create_from_date_string", 1, 1, (ctx, a) -> {
            String datetime = PhpOps.toStr(a[0]);
            TimelibErrorContainer[] err = new TimelibErrorContainer[1];
            TimelibTime time = ParseDate.strtotime(datetime, err, ZoneinfoDb.instance());
            if (err[0] != null && err[0].errorCount > 0) {
                TimelibErrorMessage first = err[0].errorMessages.get(0);
                char ch = first.character != 0 ? first.character : ' ';
                ctx.warning(String.format("Unknown or bad format (%s) at position %d (%c): %s",
                        datetime, first.position, ch, first.message), 0);
                return false;
            }
            if (time.haveDate != 0 || time.haveTime != 0 || time.haveZone != 0) {
                ctx.warning("String '" + datetime + "' contains non-relative elements", 0);
                return false;
            }
            PhpClass cls = ctx.lookupClass("DateInterval");
            PhpObject obj = new PhpObject(cls, ctx.nextObjectId());
            cls.populateDefaults(obj);
            DateTimeClasses.DateIntervalState state = new DateTimeClasses.DateIntervalState(time.relative.copy());
            state.civilOrWall = 1; // PHP_DATE_CIVIL
            state.fromString = true;
            state.dateString = datetime;
            state.initialized = true;
            obj.setNativeState(state);
            DateTimeClasses.syncPropertiesFromRelTime(obj, time.relative, true);
            return obj;
        });

        Builtins.def("date_interval_format", 2, 2, (ctx, a) -> {
            if (!(a[0] instanceof PhpObject obj) || !(obj.nativeState() instanceof DateTimeClasses.DateIntervalState state)) {
                throw new PhpError(PhpError.TYPE_ERROR,
                        "date_interval_format(): Argument #1 ($object) must be of type DateInterval", null);
            }
            if (!state.initialized) {
                throw new PhpError("Object of type DateInterval has not been correctly initialized", null);
            }
            DateTimeClasses.syncRelTimeFromProperties(obj, state.diff);
            String fmt = PhpOps.toStr(a[1]);
            return dev.phpruntime.timelib.Interval.format(fmt, state.diff);
        });

        Builtins.def("date_create", 0, 2, (ctx, a) -> {
            String timeStr = a.length >= 1 && a[0] != PhpNull.INSTANCE ? PhpOps.toStr(a[0]) : "now";
            PhpObject tzObj = (a.length >= 2 && a[1] instanceof PhpObject o && o.nativeState() instanceof DateTimeClasses.DateTimeZoneState) ? (PhpObject) a[1] : null;
            TimelibTime time = DateTimeClasses.dateInitialize(ctx, timeStr, null, tzObj, false, false);
            if (time == null) return false;
            PhpClass cls = ctx.lookupClass("DateTime");
            PhpObject obj = new PhpObject(cls, ctx.nextObjectId());
            obj.setNativeState(new DateTimeClasses.DateTimeState(time));
            return obj;
        });

        Builtins.def("date_create_immutable", 0, 2, (ctx, a) -> {
            String timeStr = a.length >= 1 && a[0] != PhpNull.INSTANCE ? PhpOps.toStr(a[0]) : "now";
            PhpObject tzObj = (a.length >= 2 && a[1] instanceof PhpObject o && o.nativeState() instanceof DateTimeClasses.DateTimeZoneState) ? (PhpObject) a[1] : null;
            TimelibTime time = DateTimeClasses.dateInitialize(ctx, timeStr, null, tzObj, false, false);
            if (time == null) return false;
            PhpClass cls = ctx.lookupClass("DateTimeImmutable");
            PhpObject obj = new PhpObject(cls, ctx.nextObjectId());
            obj.setNativeState(new DateTimeClasses.DateTimeState(time));
            return obj;
        });

        Builtins.def("date_create_from_format", 2, 3, (ctx, a) -> {
            String fmt = PhpOps.toStr(a[0]);
            String dt = PhpOps.toStr(a[1]);
            PhpObject tzObj = (a.length >= 3 && a[2] instanceof PhpObject o && o.nativeState() instanceof DateTimeClasses.DateTimeZoneState) ? (PhpObject) a[2] : null;
            TimelibTime time = DateTimeClasses.dateInitialize(ctx, dt, fmt, tzObj, false, true);
            if (time == null) return false;
            PhpClass cls = ctx.lookupClass("DateTime");
            PhpObject obj = new PhpObject(cls, ctx.nextObjectId());
            obj.setNativeState(new DateTimeClasses.DateTimeState(time));
            return obj;
        });

        Builtins.def("date_create_immutable_from_format", 2, 3, (ctx, a) -> {
            String fmt = PhpOps.toStr(a[0]);
            String dt = PhpOps.toStr(a[1]);
            PhpObject tzObj = (a.length >= 3 && a[2] instanceof PhpObject o && o.nativeState() instanceof DateTimeClasses.DateTimeZoneState) ? (PhpObject) a[2] : null;
            TimelibTime time = DateTimeClasses.dateInitialize(ctx, dt, fmt, tzObj, false, true);
            if (time == null) return false;
            PhpClass cls = ctx.lookupClass("DateTimeImmutable");
            PhpObject obj = new PhpObject(cls, ctx.nextObjectId());
            obj.setNativeState(new DateTimeClasses.DateTimeState(time));
            return obj;
        });

        Builtins.def("date_diff", 2, 3, (ctx, a) -> {
            if (!(a[0] instanceof PhpObject o1) || !(a[1] instanceof PhpObject o2)) {
                throw new PhpError(PhpError.TYPE_ERROR, "date_diff(): Arguments must be of type DateTimeInterface", null);
            }
            boolean abs = a.length >= 3 && PhpOps.toBool(a[2]);
            return DateTimeClasses.phpDateDiff(ctx, o1, o2, abs);
        });

        Builtins.def("date_format", 2, 2, (ctx, a) -> {
            if (!(a[0] instanceof PhpObject obj) || !(obj.nativeState() instanceof DateTimeClasses.DateTimeState state) || state.time == null) {
                throw new PhpError(PhpError.TYPE_ERROR, "date_format(): Argument #1 ($object) must be of type DateTimeInterface", null);
            }
            String fmt = PhpOps.toStr(a[1]);
            return dateFormat(fmt, state.time, state.time.isLocaltime != 0);
        });

        Builtins.def("date_modify", 2, 2, (ctx, a) -> {
            if (!(a[0] instanceof PhpObject obj) || !(obj.nativeState() instanceof DateTimeClasses.DateTimeState state) || state.time == null) {
                throw new PhpError(PhpError.TYPE_ERROR, "date_modify(): Argument #1 ($object) must be of type DateTime", null);
            }
            String mod = PhpOps.toStr(a[1]);
            if (!DateTimeClasses.phpDateModify(ctx, obj, mod, false)) {
                return false;
            }
            return obj;
        });

        Builtins.def("date_add", 2, 2, (ctx, a) -> {
            if (!(a[0] instanceof PhpObject obj) || !(obj.nativeState() instanceof DateTimeClasses.DateTimeState)) {
                throw new PhpError(PhpError.TYPE_ERROR, "date_add(): Argument #1 ($object) must be of type DateTime", null);
            }
            if (!(a[1] instanceof PhpObject intObj) || !(intObj.nativeState() instanceof DateTimeClasses.DateIntervalState)) {
                throw new PhpError(PhpError.TYPE_ERROR, "date_add(): Argument #2 ($interval) must be of type DateInterval", null);
            }
            DateTimeClasses.phpDateAdd(obj, intObj);
            return obj;
        });

        Builtins.def("date_sub", 2, 2, (ctx, a) -> {
            if (!(a[0] instanceof PhpObject obj) || !(obj.nativeState() instanceof DateTimeClasses.DateTimeState)) {
                throw new PhpError(PhpError.TYPE_ERROR, "date_sub(): Argument #1 ($object) must be of type DateTime", null);
            }
            if (!(a[1] instanceof PhpObject intObj) || !(intObj.nativeState() instanceof DateTimeClasses.DateIntervalState)) {
                throw new PhpError(PhpError.TYPE_ERROR, "date_sub(): Argument #2 ($interval) must be of type DateInterval", null);
            }
            DateTimeClasses.phpDateSub(obj, intObj);
            return obj;
        });

        Builtins.def("date_timezone_get", 1, 1, (ctx, a) -> {
            if (!(a[0] instanceof PhpObject obj) || !(obj.nativeState() instanceof DateTimeClasses.DateTimeState)) {
                throw new PhpError(PhpError.TYPE_ERROR, "date_timezone_get(): Argument #1 ($object) must be of type DateTimeInterface", null);
            }
            return DateTimeClasses.phpDateTimezoneGet(ctx, obj);
        });

        Builtins.def("date_timezone_set", 2, 2, (ctx, a) -> {
            if (!(a[0] instanceof PhpObject obj) || !(obj.nativeState() instanceof DateTimeClasses.DateTimeState)) {
                throw new PhpError(PhpError.TYPE_ERROR, "date_timezone_set(): Argument #1 ($object) must be of type DateTime", null);
            }
            if (!(a[1] instanceof PhpObject tzObj) || !(tzObj.nativeState() instanceof DateTimeClasses.DateTimeZoneState)) {
                throw new PhpError(PhpError.TYPE_ERROR, "date_timezone_set(): Argument #2 ($timezone) must be of type DateTimeZone", null);
            }
            DateTimeClasses.phpDateTimezoneSet(obj, tzObj);
            return obj;
        });

        Builtins.def("date_offset_get", 1, 1, (ctx, a) -> {
            if (!(a[0] instanceof PhpObject obj) || !(obj.nativeState() instanceof DateTimeClasses.DateTimeState state) || state.time == null) {
                throw new PhpError(PhpError.TYPE_ERROR, "date_offset_get(): Argument #1 ($object) must be of type DateTimeInterface", null);
            }
            return (long) state.time.z;
        });

        Builtins.def("date_timestamp_get", 1, 1, (ctx, a) -> {
            if (!(a[0] instanceof PhpObject obj) || !(obj.nativeState() instanceof DateTimeClasses.DateTimeState state) || state.time == null) {
                throw new PhpError(PhpError.TYPE_ERROR, "date_timestamp_get(): Argument #1 ($object) must be of type DateTimeInterface", null);
            }
            return state.time.sse;
        });

        Builtins.def("date_timestamp_set", 2, 2, (ctx, a) -> {
            if (!(a[0] instanceof PhpObject obj) || !(obj.nativeState() instanceof DateTimeClasses.DateTimeState)) {
                throw new PhpError(PhpError.TYPE_ERROR, "date_timestamp_set(): Argument #1 ($object) must be of type DateTime", null);
            }
            DateTimeClasses.phpDateSetTimestamp(obj, PhpOps.toLong(a[1]));
            return obj;
        });

        Builtins.def("date_time_set", 3, 5, (ctx, a) -> {
            if (!(a[0] instanceof PhpObject obj) || !(obj.nativeState() instanceof DateTimeClasses.DateTimeState)) {
                throw new PhpError(PhpError.TYPE_ERROR, "date_time_set(): Argument #1 ($object) must be of type DateTime", null);
            }
            long h = PhpOps.toLong(a[1]);
            long i = PhpOps.toLong(a[2]);
            long s = a.length >= 4 ? PhpOps.toLong(a[3]) : 0L;
            long us = a.length >= 5 ? PhpOps.toLong(a[4]) : 0L;
            DateTimeClasses.phpDateSetTime(obj, h, i, s, us);
            return obj;
        });

        Builtins.def("date_date_set", 4, 4, (ctx, a) -> {
            if (!(a[0] instanceof PhpObject obj) || !(obj.nativeState() instanceof DateTimeClasses.DateTimeState)) {
                throw new PhpError(PhpError.TYPE_ERROR, "date_date_set(): Argument #1 ($object) must be of type DateTime", null);
            }
            DateTimeClasses.phpDateSetDate(obj, PhpOps.toLong(a[1]), PhpOps.toLong(a[2]), PhpOps.toLong(a[3]));
            return obj;
        });

        Builtins.def("date_isodate_set", 3, 4, (ctx, a) -> {
            if (!(a[0] instanceof PhpObject obj) || !(obj.nativeState() instanceof DateTimeClasses.DateTimeState)) {
                throw new PhpError(PhpError.TYPE_ERROR, "date_isodate_set(): Argument #1 ($object) must be of type DateTime", null);
            }
            long y = PhpOps.toLong(a[1]);
            long w = PhpOps.toLong(a[2]);
            long d = a.length >= 4 ? PhpOps.toLong(a[3]) : 1L;
            DateTimeClasses.phpDateSetIsoDate(obj, y, w, d);
            return obj;
        });

        Builtins.def("timezone_open", 1, 1, (ctx, a) -> {
            String tz = PhpOps.toStr(a[0]);
            DateTimeClasses.DateTimeZoneState state = new DateTimeClasses.DateTimeZoneState();
            String[] errMsg = new String[1];
            if (!DateTimeClasses.timezoneInitialize(state, tz, errMsg)) {
                return false;
            }
            PhpClass cls = ctx.lookupClass("DateTimeZone");
            PhpObject obj = new PhpObject(cls, ctx.nextObjectId());
            obj.setNativeState(state);
            return obj;
        });

        Builtins.def("timezone_name_get", 1, 1, (ctx, a) -> {
            if (!(a[0] instanceof PhpObject obj) || !(obj.nativeState() instanceof DateTimeClasses.DateTimeZoneState state) || !state.initialized) {
                throw new PhpError(PhpError.TYPE_ERROR, "timezone_name_get(): Argument #1 ($object) must be of type DateTimeZone", null);
            }
            return state.name();
        });

        Builtins.def("timezone_offset_get", 2, 2, (ctx, a) -> {
            if (!(a[0] instanceof PhpObject obj) || !(obj.nativeState() instanceof DateTimeClasses.DateTimeZoneState state) || !state.initialized) {
                throw new PhpError(PhpError.TYPE_ERROR, "timezone_offset_get(): Argument #1 ($object) must be of type DateTimeZone", null);
            }
            if (!(a[1] instanceof PhpObject dtObj) || !(dtObj.nativeState() instanceof DateTimeClasses.DateTimeState dtState) || dtState.time == null) {
                throw new PhpError(PhpError.TYPE_ERROR, "timezone_offset_get(): Argument #2 ($datetime) must be of type DateTimeInterface", null);
            }
            return switch (state.type) {
                case TIMELIB_ZONETYPE_ID -> {
                    TimelibTzinfo.TimelibTimeOffset info = ParseTz.getTimeZoneInfo(dtState.time.sse, state.tzInfo);
                    yield (long) info.offset;
                }
                case TIMELIB_ZONETYPE_OFFSET -> (long) state.utcOffset;
                case TIMELIB_ZONETYPE_ABBR -> (long) state.utcOffset + (state.dst * 3600L);
                default -> 0L;
            };
        });

        Builtins.def("timezone_identifiers_list", 0, 2, (ctx, a) -> {
            long group = a.length >= 1 ? PhpOps.toLong(a[0]) : DateTimeClasses.GROUP_ALL;
            return DateTimeClasses.listIdentifiersPublic(group);
        });

        Builtins.def("timezone_transitions_get", 1, 3, (ctx, a) -> {
            if (!(a[0] instanceof PhpObject obj) || !(obj.nativeState() instanceof DateTimeClasses.DateTimeZoneState state) || !state.initialized) {
                throw new PhpError(PhpError.TYPE_ERROR, "timezone_transitions_get(): Argument #1 ($object) must be of type DateTimeZone", null);
            }
            if (state.type != TIMELIB_ZONETYPE_ID || state.tzInfo == null) {
                return false;
            }
            long begin = a.length >= 2 ? PhpOps.toLong(a[1]) : Long.MIN_VALUE;
            long end = a.length >= 3 ? PhpOps.toLong(a[2]) : Integer.MAX_VALUE;
            return DateTimeClasses.buildTransitions(state.tzInfo, begin, end);
        });

        Builtins.def("timezone_location_get", 1, 1, (ctx, a) -> {
            if (!(a[0] instanceof PhpObject obj) || !(obj.nativeState() instanceof DateTimeClasses.DateTimeZoneState state) || !state.initialized) {
                throw new PhpError(PhpError.TYPE_ERROR, "timezone_location_get(): Argument #1 ($object) must be of type DateTimeZone", null);
            }
            if (state.type != TIMELIB_ZONETYPE_ID || state.tzInfo == null) {
                return false;
            }
            PhpArray arr = new PhpArray();
            arr.put("country_code", state.tzInfo.location.countryCodeString());
            arr.put("latitude", state.tzInfo.location.latitude);
            arr.put("longitude", state.tzInfo.location.longitude);
            arr.put("comments", state.tzInfo.location.comments != null ? state.tzInfo.location.comments : "");
            return arr;
        });

        Builtins.def("timezone_abbreviations_list", 0, 0, (ctx, a) -> DateTimeClasses.buildAbbreviationsList());
        Builtins.def("date_get_last_errors", 0, 0, (ctx, a) -> errorContainerToPhp(ContextState.of(ctx).lastDateErrors));
    }

    static Object errorContainerToPhp(TimelibErrorContainer error) {
        if (error == null || (error.warningCount == 0 && error.errorCount == 0)) {
            return false;
        }
        PhpArray ret = new PhpArray();
        ret.put("warning_count", (long) error.warningCount);
        PhpArray warnings = new PhpArray();
        if (error.warningMessages != null) {
            for (TimelibErrorMessage msg : error.warningMessages) {
                warnings.put((long) msg.position, msg.message);
            }
        }
        ret.put("warnings", warnings);

        ret.put("error_count", (long) error.errorCount);
        PhpArray errs = new PhpArray();
        if (error.errorMessages != null) {
            for (TimelibErrorMessage msg : error.errorMessages) {
                errs.put((long) msg.position, msg.message);
            }
        }
        ret.put("errors", errs);
        return ret;
    }

    /** C: {@code get_timezone_info} */
    @TruffleBoundary
    static TimelibTzinfo getTimezoneInfo(PhpContext ctx) {
        String name = ContextState.of(ctx).timezoneName;
        if (name == null || name.isEmpty()) {
            name = "UTC";
        }
        int[] err = new int[1];
        TimelibTzinfo tzi = ZoneinfoDb.instance().parseTzfile(name, err);
        if (tzi == null) {
            tzi = ZoneinfoDb.instance().parseTzfile("UTC", err);
        }
        return tzi;
    }

    private static long timestamp(Object[] a, int i) {
        return a.length > i && a[i] != PhpNull.INSTANCE ? PhpOps.toLong(a[i]) : (System.currentTimeMillis() / 1000);
    }

    private static String englishSuffix(long number) {
        if (number >= 10 && number <= 19) {
            return "th";
        }
        return switch ((int) (number % 10)) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
    }

    /** C: {@code date_format} */
    @TruffleBoundary
    static String dateFormat(String format, TimelibTime t, boolean localtime) {
        if (format.isEmpty()) {
            return "";
        }

        TimelibTzinfo.TimelibTimeOffset offset = null;
        if (localtime) {
            if (t.zoneType == TIMELIB_ZONETYPE_ABBR) {
                offset = new TimelibTzinfo.TimelibTimeOffset();
                offset.offset = t.z + (t.dst * 3600);
                offset.isDst = t.dst;
                offset.abbr = t.tzAbbr;
            } else if (t.zoneType == TIMELIB_ZONETYPE_OFFSET) {
                offset = new TimelibTzinfo.TimelibTimeOffset();
                offset.offset = t.z;
                offset.isDst = 0;
                char sign = offset.offset < 0 ? '-' : '+';
                int absOff = Math.abs(offset.offset);
                offset.abbr = String.format(Locale.ROOT, "GMT%c%02d%02d", sign, absOff / 3600, (absOff % 3600) / 60);
            } else if (t.zoneType == TIMELIB_ZONETYPE_ID && t.tzInfo != null) {
                offset = ParseTz.getTimeZoneInfo(t.sse, t.tzInfo);
            } else {
                offset = new TimelibTzinfo.TimelibTimeOffset();
            }
        }

        StringBuilder sb = new StringBuilder();
        long[] isoweek = new long[1];
        long[] isoyear = new long[1];
        boolean weekYearSet = false;

        int len = format.length();
        for (int i = 0; i < len; i++) {
            char c = format.charAt(i);
            switch (c) {
                /* day */
                case 'd' -> sb.append(String.format(Locale.ROOT, "%02d", t.d));
                case 'D' -> {
                    long dow = Dow.dayOfWeek(t.y, t.m, t.d);
                    sb.append((dow >= 0 && dow < 7) ? DAY_SHORT_NAMES[(int) dow] : "Unknown");
                }
                case 'j' -> sb.append(t.d);
                case 'l' -> {
                    long dow = Dow.dayOfWeek(t.y, t.m, t.d);
                    sb.append((dow >= 0 && dow < 7) ? DAY_FULL_NAMES[(int) dow] : "Unknown");
                }
                case 'S' -> sb.append(englishSuffix(t.d));
                case 'w' -> sb.append(Dow.dayOfWeek(t.y, t.m, t.d));
                case 'N' -> sb.append(Dow.isoDayOfWeek(t.y, t.m, t.d));
                case 'z' -> sb.append(Dow.dayOfYear(t.y, t.m, t.d));

                /* week */
                case 'W' -> {
                    if (!weekYearSet) {
                        Dow.isoweekFromDate(t.y, t.m, t.d, isoweek, isoyear);
                        weekYearSet = true;
                    }
                    sb.append(String.format(Locale.ROOT, "%02d", isoweek[0]));
                }
                case 'o' -> {
                    if (!weekYearSet) {
                        Dow.isoweekFromDate(t.y, t.m, t.d, isoweek, isoyear);
                        weekYearSet = true;
                    }
                    sb.append(isoyear[0]);
                }

                /* month */
                case 'F' -> sb.append((t.m >= 1 && t.m <= 12) ? MON_FULL_NAMES[(int) t.m - 1] : "");
                case 'm' -> sb.append(String.format(Locale.ROOT, "%02d", t.m));
                case 'M' -> sb.append((t.m >= 1 && t.m <= 12) ? MON_SHORT_NAMES[(int) t.m - 1] : "");
                case 'n' -> sb.append(t.m);
                case 't' -> sb.append(Dow.daysInMonth(t.y, t.m));

                /* year */
                case 'L' -> sb.append(TimelibConstants.isLeap(t.y) ? "1" : "0");
                case 'y' -> sb.append(String.format(Locale.ROOT, "%02d", (int) (t.y % 100)));
                case 'Y' -> {
                    if (t.y < 0) {
                        sb.append('-');
                    }
                    sb.append(String.format(Locale.ROOT, "%04d", Math.abs(t.y)));
                }
                case 'x' -> {
                    if (t.y < 0) {
                        sb.append('-');
                    } else if (t.y >= 10000) {
                        sb.append('+');
                    }
                    sb.append(String.format(Locale.ROOT, "%04d", Math.abs(t.y)));
                }
                case 'X' -> {
                    sb.append(t.y < 0 ? '-' : '+');
                    sb.append(String.format(Locale.ROOT, "%04d", Math.abs(t.y)));
                }

                /* time */
                case 'a' -> sb.append(t.h >= 12 ? "pm" : "am");
                case 'A' -> sb.append(t.h >= 12 ? "PM" : "AM");
                case 'B' -> {
                    long sse = t.sse;
                    long r = (sse - (sse - (((sse % 86400) + 3600)))) * 10;
                    if (r < 0) {
                        r += 864000;
                    }
                    r = (r / 864) % 1000;
                    sb.append(String.format(Locale.ROOT, "%03d", r));
                }
                case 'g' -> sb.append((t.h % 12 != 0) ? (t.h % 12) : 12);
                case 'G' -> sb.append(t.h);
                case 'h' -> sb.append(String.format(Locale.ROOT, "%02d", (t.h % 12 != 0) ? (t.h % 12) : 12));
                case 'H' -> sb.append(String.format(Locale.ROOT, "%02d", t.h));
                case 'i' -> sb.append(String.format(Locale.ROOT, "%02d", t.i));
                case 's' -> sb.append(String.format(Locale.ROOT, "%02d", t.s));
                case 'u' -> sb.append(String.format(Locale.ROOT, "%06d", t.us >= 0 ? t.us : 0));
                case 'v' -> sb.append(String.format(Locale.ROOT, "%03d", (t.us >= 0 ? t.us : 0) / 1000));

                /* timezone */
                case 'I' -> sb.append((localtime && offset != null && offset.isDst != 0) ? "1" : "0");
                case 'p' -> {
                    if (!localtime || (offset != null && ("UTC".equals(offset.abbr) || "Z".equals(offset.abbr) || "GMT+0000".equals(offset.abbr)))) {
                        sb.append("Z");
                        break;
                    }
                    // Fallthrough to 'P'
                    char sign = (localtime && offset != null && offset.offset < 0) ? '-' : '+';
                    int absOff = (localtime && offset != null) ? Math.abs(offset.offset) : 0;
                    sb.append(String.format(Locale.ROOT, "%c%02d:%02d", sign, absOff / 3600, (absOff % 3600) / 60));
                }
                case 'P' -> {
                    char sign = (localtime && offset != null && offset.offset < 0) ? '-' : '+';
                    int absOff = (localtime && offset != null) ? Math.abs(offset.offset) : 0;
                    sb.append(String.format(Locale.ROOT, "%c%02d:%02d", sign, absOff / 3600, (absOff % 3600) / 60));
                }
                case 'O' -> {
                    char sign = (localtime && offset != null && offset.offset < 0) ? '-' : '+';
                    int absOff = (localtime && offset != null) ? Math.abs(offset.offset) : 0;
                    sb.append(String.format(Locale.ROOT, "%c%02d%02d", sign, absOff / 3600, (absOff % 3600) / 60));
                }
                case 'T' -> sb.append(localtime ? (offset != null && offset.abbr != null ? offset.abbr : "") : "GMT");
                case 'e' -> {
                    if (!localtime) {
                        sb.append("UTC");
                    } else {
                        switch (t.zoneType) {
                            case TIMELIB_ZONETYPE_ID -> sb.append(t.tzInfo != null ? t.tzInfo.name : "UTC");
                            case TIMELIB_ZONETYPE_ABBR -> sb.append(offset != null && offset.abbr != null ? offset.abbr : "");
                            case TIMELIB_ZONETYPE_OFFSET -> {
                                int seconds = offset != null ? offset.offset % 60 : 0;
                                char sign = offset != null && offset.offset < 0 ? '-' : '+';
                                int absOff = offset != null ? Math.abs(offset.offset) : 0;
                                if (seconds == 0) {
                                    sb.append(String.format(Locale.ROOT, "%c%02d:%02d", sign, absOff / 3600, (absOff % 3600) / 60));
                                } else {
                                    sb.append(String.format(Locale.ROOT, "%c%02d:%02d:%02d", sign, absOff / 3600, (absOff % 3600) / 60, Math.abs(seconds)));
                                }
                            }
                            default -> sb.append("UTC");
                        }
                    }
                }
                case 'Z' -> sb.append(localtime && offset != null ? offset.offset : 0);

                /* full date/time */
                case 'c' -> {
                    char sign = (localtime && offset != null && offset.offset < 0) ? '-' : '+';
                    int absOff = (localtime && offset != null) ? Math.abs(offset.offset) : 0;
                    sb.append(String.format(Locale.ROOT, "%04d-%02d-%02dT%02d:%02d:%02d%c%02d:%02d",
                            t.y, t.m, t.d, t.h, t.i, t.s, sign, absOff / 3600, (absOff % 3600) / 60));
                }
                case 'r' -> {
                    char sign = (localtime && offset != null && offset.offset < 0) ? '-' : '+';
                    int absOff = (localtime && offset != null) ? Math.abs(offset.offset) : 0;
                    long dow = Dow.dayOfWeek(t.y, t.m, t.d);
                    String dowName = (dow >= 0 && dow < 7) ? DAY_SHORT_NAMES[(int) dow] : "Unknown";
                    String monName = (t.m >= 1 && t.m <= 12) ? MON_SHORT_NAMES[(int) t.m - 1] : "";
                    sb.append(String.format(Locale.ROOT, "%s, %02d %s %04d %02d:%02d:%02d %c%02d%02d",
                            dowName, t.d, monName, t.y, t.h, t.i, t.s, sign, absOff / 3600, (absOff % 3600) / 60));
                }
                case 'U' -> sb.append(t.sse);

                case '\\' -> {
                    if (i + 1 < len) {
                        sb.append(format.charAt(++i));
                    }
                }
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** C: {@code php_idate} */
    @TruffleBoundary
    private static Object idate(PhpContext ctx, Object[] a) {
        String format = PhpOps.toStr(a[0]);
        if (format.length() != 1) {
            ctx.warning("idate(): idate format is one char", 0);
            return false;
        }

        long ts = timestamp(a, 1);
        TimelibTime t = new TimelibTime();
        TimelibTzinfo tzi = getTimezoneInfo(ctx);
        t.tzInfo = tzi;
        t.zoneType = TIMELIB_ZONETYPE_ID;
        Unixtime2Tm.unixtime2local(t, ts);

        TimelibTzinfo.TimelibTimeOffset offset;
        if (t.zoneType == TIMELIB_ZONETYPE_ABBR) {
            offset = new TimelibTzinfo.TimelibTimeOffset();
            offset.offset = t.z + (t.dst * 3600);
            offset.isDst = t.dst;
            offset.abbr = t.tzAbbr;
        } else if (t.zoneType == TIMELIB_ZONETYPE_OFFSET) {
            offset = new TimelibTzinfo.TimelibTimeOffset();
            offset.offset = t.z;
            offset.isDst = 0;
            char sign = offset.offset < 0 ? '-' : '+';
            int absOff = Math.abs(offset.offset);
            offset.abbr = String.format(Locale.ROOT, "GMT%c%02d%02d", sign, absOff / 3600, (absOff % 3600) / 60);
        } else {
            offset = ParseTz.getTimeZoneInfo(t.sse, t.tzInfo);
        }

        long[] isoweek = new long[1];
        long[] isoyear = new long[1];
        Dow.isoweekFromDate(t.y, t.m, t.d, isoweek, isoyear);

        char c = format.charAt(0);
        long retval = switch (c) {
            case 'd', 'j' -> t.d;
            case 'N' -> Dow.isoDayOfWeek(t.y, t.m, t.d);
            case 'w' -> Dow.dayOfWeek(t.y, t.m, t.d);
            case 'z' -> Dow.dayOfYear(t.y, t.m, t.d);
            case 'W' -> isoweek[0];
            case 'm', 'n' -> t.m;
            case 't' -> Dow.daysInMonth(t.y, t.m);
            case 'L' -> TimelibConstants.isLeap(t.y) ? 1 : 0;
            case 'y' -> (int) (t.y % 100);
            case 'Y' -> (int) t.y;
            case 'o' -> (int) isoyear[0];
            case 'B' -> {
                long sse = t.sse;
                long r = (sse - (sse - (((sse % 86400) + 3600)))) * 10;
                if (r < 0) {
                    r += 864000;
                }
                yield (int) ((r / 864) % 1000);
            }
            case 'g', 'h' -> (t.h % 12 != 0) ? (t.h % 12) : 12;
            case 'H', 'G' -> t.h;
            case 'i' -> t.i;
            case 's' -> t.s;
            case 'I' -> offset.isDst;
            case 'Z' -> offset.offset;
            case 'U' -> (int) t.sse;
            default -> -1;
        };

        if (retval == -1) {
            ctx.warning("idate(): Unrecognized date format token", 0);
            return false;
        }
        return retval;
    }

    /** C: {@code PHP_FUNCTION(getdate)} */
    @TruffleBoundary
    private static PhpArray getdate(PhpContext ctx, Object[] a) {
        long ts = timestamp(a, 0);
        TimelibTime t = new TimelibTime();
        TimelibTzinfo tzi = getTimezoneInfo(ctx);
        t.tzInfo = tzi;
        t.zoneType = TIMELIB_ZONETYPE_ID;
        Unixtime2Tm.unixtime2local(t, ts);

        PhpArray ret = new PhpArray();
        ret.put("seconds", t.s);
        ret.put("minutes", t.i);
        ret.put("hours", t.h);
        ret.put("mday", t.d);
        long dow = Dow.dayOfWeek(t.y, t.m, t.d);
        ret.put("wday", dow);
        ret.put("mon", t.m);
        ret.put("year", t.y);
        ret.put("yday", Dow.dayOfYear(t.y, t.m, t.d));
        ret.put("weekday", (dow >= 0 && dow < 7) ? DAY_FULL_NAMES[(int) dow] : "Unknown");
        ret.put("month", (t.m >= 1 && t.m <= 12) ? MON_FULL_NAMES[(int) t.m - 1] : "");
        ret.put(0L, ts);
        return ret;
    }

    /** C: {@code PHP_FUNCTION(localtime)} */
    @TruffleBoundary
    private static PhpArray localtime(PhpContext ctx, Object[] a) {
        long ts = timestamp(a, 0);
        boolean associative = a.length > 1 && a[1] != PhpNull.INSTANCE && PhpOps.toBool(a[1]);

        TimelibTime t = new TimelibTime();
        TimelibTzinfo tzi = getTimezoneInfo(ctx);
        t.tzInfo = tzi;
        t.zoneType = TIMELIB_ZONETYPE_ID;
        Unixtime2Tm.unixtime2local(t, ts);

        PhpArray ret = new PhpArray();
        if (associative) {
            ret.put("tm_sec", t.s);
            ret.put("tm_min", t.i);
            ret.put("tm_hour", t.h);
            ret.put("tm_mday", t.d);
            ret.put("tm_mon", t.m - 1);
            ret.put("tm_year", t.y - 1900);
            ret.put("tm_wday", Dow.dayOfWeek(t.y, t.m, t.d));
            ret.put("tm_yday", Dow.dayOfYear(t.y, t.m, t.d));
            ret.put("tm_isdst", (long) t.dst);
        } else {
            ret.append(t.s);
            ret.append(t.i);
            ret.append(t.h);
            ret.append(t.d);
            ret.append(t.m - 1);
            ret.append(t.y - 1900);
            ret.append(Dow.dayOfWeek(t.y, t.m, t.d));
            ret.append(Dow.dayOfYear(t.y, t.m, t.d));
            ret.append((long) t.dst);
        }
        return ret;
    }

    /** C: {@code php_mktime} */
    @TruffleBoundary
    private static Object phpMktime(PhpContext ctx, Object[] a, boolean gmt) {
        long nowTs = System.currentTimeMillis() / 1000;
        TimelibTime now = new TimelibTime();
        TimelibTzinfo tzi = null;
        if (gmt) {
            Unixtime2Tm.unixtime2gmt(now, nowTs);
        } else {
            tzi = getTimezoneInfo(ctx);
            now.tzInfo = tzi;
            now.zoneType = TIMELIB_ZONETYPE_ID;
            Unixtime2Tm.unixtime2local(now, nowTs);
        }

        if (a.length > 0 && a[0] != PhpNull.INSTANCE) {
            now.h = PhpOps.toLong(a[0]);
        }
        if (a.length > 1 && a[1] != PhpNull.INSTANCE) {
            now.i = PhpOps.toLong(a[1]);
        }
        if (a.length > 2 && a[2] != PhpNull.INSTANCE) {
            now.s = PhpOps.toLong(a[2]);
        }
        if (a.length > 3 && a[3] != PhpNull.INSTANCE) {
            now.m = PhpOps.toLong(a[3]);
        }
        if (a.length > 4 && a[4] != PhpNull.INSTANCE) {
            now.d = PhpOps.toLong(a[4]);
        }
        if (a.length > 5 && a[5] != PhpNull.INSTANCE) {
            long yea = PhpOps.toLong(a[5]);
            if (yea >= 0 && yea < 70) {
                yea += 2000;
            } else if (yea >= 70 && yea <= 100) {
                yea += 1900;
            }
            now.y = yea;
        }

        if (gmt) {
            Tm2Unixtime.updateTs(now, null);
        } else {
            Tm2Unixtime.updateTs(now, tzi);
        }

        return Timelib.dateToInt(now, null);
    }

    /** C: {@code PHP_FUNCTION(strtotime)} */
    @TruffleBoundary
    private static Object strtotime(PhpContext ctx, Object[] a) {
        String times = PhpOps.toStr(a[0]);
        if (times.isEmpty()) {
            return false;
        }
        TimelibTzinfo tzi = getTimezoneInfo(ctx);
        long baseTs = (a.length > 1 && a[1] != PhpNull.INSTANCE)
                ? PhpOps.toLong(a[1])
                : (System.currentTimeMillis() / 1000);

        TimelibTime now = new TimelibTime();
        now.tzInfo = tzi;
        now.zoneType = TIMELIB_ZONETYPE_ID;
        Unixtime2Tm.unixtime2local(now, baseTs);

        TimelibErrorContainer[] errors = new TimelibErrorContainer[1];
        TimelibTime t = ParseDate.strtotime(times, errors, ZoneinfoDb.instance());
        if (errors[0] != null && errors[0].errorCount > 0) {
            return false;
        }

        ParseDate.fillHoles(t, now, TIMELIB_NO_CLONE);
        Tm2Unixtime.updateTs(t, tzi);
        return Timelib.dateToInt(t, null);
    }

    private static void setTimeElement(PhpArray arr, String name, long val) {
        if (val == TIMELIB_UNSET) {
            arr.put(name, false);
        } else {
            arr.put(name, val);
        }
    }

    @TruffleBoundary
    private static PhpArray dateParse(String datetime) {
        TimelibErrorContainer[] errors = new TimelibErrorContainer[1];
        TimelibTime parsed = ParseDate.strtotime(datetime, errors, ZoneinfoDb.instance());
        return returnParsedTime(parsed, errors[0]);
    }

    @TruffleBoundary
    private static PhpArray dateParseFromFormat(String format, String datetime) {
        TimelibErrorContainer[] errors = new TimelibErrorContainer[1];
        TimelibTime parsed = ParseDate.parseFromFormat(format, datetime, errors, ZoneinfoDb.instance());
        return returnParsedTime(parsed, errors[0]);
    }

    /** C: {@code php_date_do_return_parsed_time} */
    private static PhpArray returnParsedTime(TimelibTime parsedTime, TimelibErrorContainer error) {
        PhpArray ret = new PhpArray();
        setTimeElement(ret, "year", parsedTime.y);
        setTimeElement(ret, "month", parsedTime.m);
        setTimeElement(ret, "day", parsedTime.d);
        setTimeElement(ret, "hour", parsedTime.h);
        setTimeElement(ret, "minute", parsedTime.i);
        setTimeElement(ret, "second", parsedTime.s);

        if (parsedTime.us == TIMELIB_UNSET) {
            ret.put("fraction", false);
        } else {
            ret.put("fraction", (double) parsedTime.us / 1000000.0);
        }

        ret.put("warning_count", (long) (error != null ? error.warningCount : 0));
        PhpArray warnings = new PhpArray();
        if (error != null) {
            for (TimelibErrorMessage msg : error.warningMessages) {
                warnings.put((long) msg.position, msg.message);
            }
        }
        ret.put("warnings", warnings);

        ret.put("error_count", (long) (error != null ? error.errorCount : 0));
        PhpArray errs = new PhpArray();
        if (error != null) {
            for (TimelibErrorMessage msg : error.errorMessages) {
                errs.put((long) msg.position, msg.message);
            }
        }
        ret.put("errors", errs);

        ret.put("is_localtime", parsedTime.isLocaltime != 0);

        if (parsedTime.isLocaltime != 0) {
            setTimeElement(ret, "zone_type", parsedTime.zoneType);
            switch (parsedTime.zoneType) {
                case TIMELIB_ZONETYPE_OFFSET -> {
                    setTimeElement(ret, "zone", parsedTime.z);
                    ret.put("is_dst", parsedTime.dst != 0);
                }
                case TIMELIB_ZONETYPE_ID -> {
                    if (parsedTime.tzAbbr != null) {
                        ret.put("tz_abbr", parsedTime.tzAbbr);
                    }
                    if (parsedTime.tzInfo != null) {
                        ret.put("tz_id", parsedTime.tzInfo.name);
                    }
                }
                case TIMELIB_ZONETYPE_ABBR -> {
                    setTimeElement(ret, "zone", parsedTime.z);
                    ret.put("is_dst", parsedTime.dst != 0);
                    ret.put("tz_abbr", parsedTime.tzAbbr != null ? parsedTime.tzAbbr : "");
                }
            }
        }

        if (parsedTime.haveRelative != 0) {
            PhpArray rel = new PhpArray();
            rel.put("year", parsedTime.relative.y);
            rel.put("month", parsedTime.relative.m);
            rel.put("day", parsedTime.relative.d);
            rel.put("hour", parsedTime.relative.h);
            rel.put("minute", parsedTime.relative.i);
            rel.put("second", parsedTime.relative.s);
            if (parsedTime.relative.haveWeekdayRelative != 0) {
                rel.put("weekday", (long) parsedTime.relative.weekday);
            }
            if (parsedTime.relative.haveSpecialRelative != 0 && parsedTime.relative.special.type == TIMELIB_SPECIAL_WEEKDAY) {
                rel.put("weekdays", parsedTime.relative.special.amount);
            }
            if (parsedTime.relative.firstLastDayOf != 0) {
                String key = parsedTime.relative.firstLastDayOf == TIMELIB_SPECIAL_FIRST_DAY_OF_MONTH
                        ? "first_day_of_month" : "last_day_of_month";
                rel.put(key, true);
            }
            ret.put("relative", rel);
        }

        return ret;
    }
}
