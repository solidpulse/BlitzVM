// Glue code translated from php-src ext/date/php_date.c (PHP License 3.01).

package dev.phpruntime.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.object.DynamicObject;
import dev.phpruntime.PhpContext;
import dev.phpruntime.PhpLanguage;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpBacktrace;
import dev.phpruntime.runtime.PhpClass;
import dev.phpruntime.runtime.PhpClassTemplate;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpException;
import dev.phpruntime.runtime.PhpExceptions;
import dev.phpruntime.runtime.PhpNativeState;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.Visibility;
import dev.phpruntime.timelib.Dow;
import dev.phpruntime.timelib.Interval;
import dev.phpruntime.timelib.ParseDate;
import dev.phpruntime.timelib.ParseIsoIntervals;
import dev.phpruntime.timelib.ParseTz;
import dev.phpruntime.timelib.TimelibErrorContainer;
import dev.phpruntime.timelib.TimelibErrorMessage;
import dev.phpruntime.timelib.TimelibRelTime;
import dev.phpruntime.timelib.TimelibTime;
import dev.phpruntime.timelib.TimelibTzinfo;
import dev.phpruntime.timelib.Tm2Unixtime;
import dev.phpruntime.timelib.Unixtime2Tm;
import dev.phpruntime.timelib.ZoneinfoDb;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.phpruntime.timelib.TimelibConstants.*;

/**
 * Built-in date/time classes and interfaces.
 *
 * <p>Phase A implements {@code DateTimeInterface} (constants) and {@code DateTimeZone} completely
 * as the pattern for Phase F ({@code DateTime}, {@code DateTimeImmutable}, {@code DateInterval},
 * {@code DatePeriod}).
 */
public final class DateTimeClasses {

    public static final long GROUP_AFRICA = 1L;
    public static final long GROUP_AMERICA = 2L;
    public static final long GROUP_ANTARCTICA = 4L;
    public static final long GROUP_ARCTIC = 8L;
    public static final long GROUP_ASIA = 16L;
    public static final long GROUP_ATLANTIC = 32L;
    public static final long GROUP_AUSTRALIA = 64L;
    public static final long GROUP_EUROPE = 128L;
    public static final long GROUP_INDIAN = 256L;
    public static final long GROUP_PACIFIC = 512L;
    public static final long GROUP_UTC = 1024L;
    public static final long GROUP_ALL = 2047L;
    public static final long GROUP_ALL_WITH_BC = 4095L;
    public static final long GROUP_PER_COUNTRY = 4096L;

    private DateTimeClasses() {
    }

    /** Native state for {@code DateTime} / {@code DateTimeImmutable} objects. */
    public static final class DateTimeState implements PhpNativeState {
        public TimelibTime time;

        public DateTimeState(TimelibTime time) {
            this.time = time;
        }

        @Override
        public PhpNativeState copy() {
            return new DateTimeState(time != null ? time.copy() : null);
        }

        @Override
        public Map<String, Object> debugProperties(PhpObject self) {
            if (time == null) {
                return null;
            }
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("date", String.format("%04d-%02d-%02d %02d:%02d:%02d.%06d",
                    time.y, time.m, time.d, time.h, time.i, time.s, time.us));
            if (time.isLocaltime != 0) {
                props.put("timezone_type", (long) time.zoneType);
                switch (time.zoneType) {
                    case TIMELIB_ZONETYPE_ID -> props.put("timezone", time.tzInfo != null ? time.tzInfo.name : "");
                    case TIMELIB_ZONETYPE_OFFSET -> props.put("timezone", formatOffsetString(time.z));
                    case TIMELIB_ZONETYPE_ABBR -> props.put("timezone", time.tzAbbr != null ? time.tzAbbr : "");
                }
            }
            return props;
        }

        @Override
        public boolean supportsCompare() {
            return true;
        }

        @Override
        public int compareTo(PhpObject other) {
            if (!(other.nativeState() instanceof DateTimeState otherState)) {
                return 0;
            }
            if (this.time == null || otherState.time == null) {
                throw new PhpError("Trying to compare an incomplete DateTime or DateTimeImmutable object", null);
            }
            int c = Long.compare(this.time.sse, otherState.time.sse);
            if (c != 0) {
                return c;
            }
            return Long.compare(this.time.us, otherState.time.us);
        }
    }

    /** Native state for {@code DateTimeZone} objects. */
    public static final class DateTimeZoneState implements PhpNativeState {
        public int type;
        public TimelibTzinfo tzInfo;
        public int utcOffset;
        public int dst;
        public String tzAbbr;
        public boolean initialized;

        @Override
        public PhpNativeState copy() {
            DateTimeZoneState c = new DateTimeZoneState();
            c.type = this.type;
            c.tzInfo = this.tzInfo; // immutable engine-wide
            c.utcOffset = this.utcOffset;
            c.dst = this.dst;
            c.tzAbbr = this.tzAbbr;
            c.initialized = this.initialized;
            return c;
        }

        public String name() {
            return switch (type) {
                case TIMELIB_ZONETYPE_ID -> tzInfo != null ? tzInfo.name : "";
                case TIMELIB_ZONETYPE_OFFSET -> formatOffsetString(utcOffset);
                case TIMELIB_ZONETYPE_ABBR -> tzAbbr != null ? tzAbbr : "";
                default -> "";
            };
        }

        @Override
        public Map<String, Object> debugProperties(PhpObject self) {
            if (!initialized) {
                return null;
            }
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("timezone_type", (long) type);
            props.put("timezone", name());
            return props;
        }
    }

    /** Native state for {@code DateInterval} objects. */
    public static final class DateIntervalState implements PhpNativeState {
        public TimelibRelTime diff;
        public boolean initialized;
        public int civilOrWall;
        public boolean fromString;
        public String dateString;

        public DateIntervalState(TimelibRelTime diff) {
            this.diff = diff;
            this.initialized = diff != null;
        }

        @Override
        public PhpNativeState copy() {
            DateIntervalState c = new DateIntervalState(diff != null ? diff.copy() : null);
            c.initialized = this.initialized;
            c.civilOrWall = this.civilOrWall;
            c.fromString = this.fromString;
            c.dateString = this.dateString;
            return c;
        }

        @Override
        public Map<String, Object> debugProperties(PhpObject self) {
            return null;
        }
    }

    public static void syncPropertiesFromRelTime(PhpObject self, TimelibRelTime diff, boolean fromString) {
        DynamicObject.PutNode put = DynamicObject.PutNode.getUncached();
        put.execute(self, "y", diff.y);
        put.execute(self, "m", diff.m);
        put.execute(self, "d", diff.d);
        put.execute(self, "h", diff.h);
        put.execute(self, "i", diff.i);
        put.execute(self, "s", diff.s);
        put.execute(self, "f", (double) diff.us / 1000000.0);
        put.execute(self, "invert", (long) diff.invert);
        put.execute(self, "days", diff.days != TIMELIB_UNSET ? (Object) diff.days : Boolean.FALSE);
        put.execute(self, "from_string", fromString);
    }

    public static void syncRelTimeFromProperties(PhpObject self, TimelibRelTime diff) {
        if (diff == null) return;
        DynamicObject.GetNode get = DynamicObject.GetNode.getUncached();
        diff.y = PhpOps.toLong(get.execute(self, "y", diff.y));
        diff.m = PhpOps.toLong(get.execute(self, "m", diff.m));
        diff.d = PhpOps.toLong(get.execute(self, "d", diff.d));
        diff.h = PhpOps.toLong(get.execute(self, "h", diff.h));
        diff.i = PhpOps.toLong(get.execute(self, "i", diff.i));
        diff.s = PhpOps.toLong(get.execute(self, "s", diff.s));
        Object fVal = get.execute(self, "f", 0.0);
        diff.us = (long) Math.round(PhpOps.toDouble(fVal) * 1000000.0);
        diff.invert = (int) PhpOps.toLong(get.execute(self, "invert", (long) diff.invert));
        Object daysVal = get.execute(self, "days", Boolean.FALSE);
        if (daysVal instanceof Boolean b && !b) {
            diff.days = TIMELIB_UNSET;
        } else {
            diff.days = PhpOps.toLong(daysVal);
        }
    }

    static String formatOffsetString(long offset) {
        long abs = Math.abs(offset);
        long hours = abs / 3600;
        long mins = (abs % 3600) / 60;
        long secs = abs % 60;
        char sign = offset < 0 ? '-' : '+';
        if (secs == 0) {
            return String.format("%c%02d:%02d", sign, hours, mins);
        }
        return String.format("%c%02d:%02d:%02d", sign, hours, mins, secs);
    }

    /** Registers built-in date/time class and interface templates. */
    public static void buildTemplates(PhpLanguage language, Map<String, PhpClassTemplate> out) {
        // 1. interface DateTimeInterface
        List<PhpClassTemplate.ConstantDecl> dtiConstants = List.of(
                constDecl("ATOM", "Y-m-d\\TH:i:sP"),
                constDecl("COOKIE", "l, d-M-Y H:i:s T"),
                constDecl("ISO8601", "Y-m-d\\TH:i:sO"),
                constDecl("ISO8601_EXPANDED", "X-m-d\\TH:i:sP"),
                constDecl("RFC822", "D, d M y H:i:s O"),
                constDecl("RFC850", "l, d-M-y H:i:s T"),
                constDecl("RFC1036", "D, d M y H:i:s O"),
                constDecl("RFC1123", "D, d M Y H:i:s O"),
                constDecl("RFC7231", "D, d M Y H:i:s \\G\\M\\T"),
                constDecl("RFC2822", "D, d M Y H:i:s O"),
                constDecl("RFC3339", "Y-m-d\\TH:i:sP"),
                constDecl("RFC3339_EXTENDED", "Y-m-d\\TH:i:s.vP"),
                constDecl("RSS", "D, d M Y H:i:s O"),
                constDecl("W3C", "Y-m-d\\TH:i:sP")
        );
        Map<String, PhpClassTemplate.MethodDecl> dtiMethods = new LinkedHashMap<>();
        addAbstractMethod(language, dtiMethods, "format");
        addAbstractMethod(language, dtiMethods, "getTimezone");
        addAbstractMethod(language, dtiMethods, "getOffset");
        addAbstractMethod(language, dtiMethods, "getTimestamp");
        addAbstractMethod(language, dtiMethods, "getMicrosecond");
        addAbstractMethod(language, dtiMethods, "diff");
        addAbstractMethod(language, dtiMethods, "__wakeup");
        addAbstractMethod(language, dtiMethods, "__serialize");
        addAbstractMethod(language, dtiMethods, "__unserialize");
        register(out, new PhpClassTemplate(
                language.nextTemplateId(), "DateTimeInterface", true, false, false, false,
                null, List.of(), List.of(), dtiConstants, dtiMethods));

        // 2. class DateTimeZone
        register(out, buildDateTimeZoneClass(language));

        // 3. class DateInterval
        register(out, buildDateIntervalClass(language));

        // 4. class DateTime
        register(out, buildDateTimeClass(language, false));

        // 5. class DateTimeImmutable
        register(out, buildDateTimeClass(language, true));

        // 6. class DatePeriod
        register(out, buildDatePeriodClass(language));
    }

    private static PhpClassTemplate.ConstantDecl constDecl(String name, Object value) {
        return new PhpClassTemplate.ConstantDecl(name, Visibility.PUBLIC, false, value, null);
    }

    private static void register(Map<String, PhpClassTemplate> out, PhpClassTemplate tpl) {
        out.put(tpl.lowerName(), tpl);
    }

    private static void addAbstractMethod(PhpLanguage language,
                                          Map<String, PhpClassTemplate.MethodDecl> map,
                                          String name) {
        String lower = name.toLowerCase();
        map.put(lower, new PhpClassTemplate.MethodDecl(
                name, lower, language.methodId(name), Visibility.PUBLIC, false, true, false, null));
    }

    private static void addMethod(PhpLanguage language,
                                  Map<String, PhpClassTemplate.MethodDecl> map,
                                  String name, boolean isStatic, PhpExceptions.BuiltinMethod impl) {
        String lower = name.toLowerCase();
        RootNode root = new PhpExceptions.BuiltinMethodRootNode(language, name, impl);
        map.put(lower, new PhpClassTemplate.MethodDecl(
                name, lower, language.methodId(name), Visibility.PUBLIC, isStatic, false, false, root.getCallTarget()));
    }

    private static PhpClassTemplate buildDateTimeZoneClass(PhpLanguage language) {
        List<PhpClassTemplate.ConstantDecl> constants = List.of(
                constDecl("AFRICA", GROUP_AFRICA),
                constDecl("AMERICA", GROUP_AMERICA),
                constDecl("ANTARCTICA", GROUP_ANTARCTICA),
                constDecl("ARCTIC", GROUP_ARCTIC),
                constDecl("ASIA", GROUP_ASIA),
                constDecl("ATLANTIC", GROUP_ATLANTIC),
                constDecl("AUSTRALIA", GROUP_AUSTRALIA),
                constDecl("EUROPE", GROUP_EUROPE),
                constDecl("INDIAN", GROUP_INDIAN),
                constDecl("PACIFIC", GROUP_PACIFIC),
                constDecl("UTC", GROUP_UTC),
                constDecl("ALL", GROUP_ALL),
                constDecl("ALL_WITH_BC", GROUP_ALL_WITH_BC),
                constDecl("PER_COUNTRY", GROUP_PER_COUNTRY)
        );

        Map<String, PhpClassTemplate.MethodDecl> methods = new LinkedHashMap<>();

        addMethod(language, methods, "__construct", false, (ctx, self, args) -> {
            if (args.length != 1) {
                throw new PhpError(PhpError.ARGUMENT_COUNT,
                        "DateTimeZone::__construct() expects exactly 1 argument, " + args.length + " given", null);
            }
            String tz = PhpOps.toStr(args[0]);
            if (tz.indexOf('\0') >= 0) {
                throw new PhpError(PhpError.VALUE_ERROR,
                        "DateTimeZone::__construct(): Argument #1 ($timezone) must not contain any null bytes", null);
            }
            DateTimeZoneState state = new DateTimeZoneState();
            String[] errMsg = new String[1];
            if (!timezoneInitialize(state, tz, errMsg)) {
                throw throwDateException(ctx, "DateInvalidTimeZoneException",
                        "DateTimeZone::__construct(): " + errMsg[0]);
            }
            self.setNativeState(state);
            return PhpNull.INSTANCE;
        });

        addMethod(language, methods, "getName", false, (ctx, self, args) -> {
            if (args.length != 0) {
                throw new PhpError(PhpError.ARGUMENT_COUNT,
                        "DateTimeZone::getName() expects exactly 0 arguments, " + args.length + " given", null);
            }
            DateTimeZoneState state = requireInitializedZone(self);
            return state.name();
        });

        addMethod(language, methods, "getOffset", false, (ctx, self, args) -> {
            if (args.length != 1) {
                throw new PhpError(PhpError.ARGUMENT_COUNT,
                        "DateTimeZone::getOffset() expects exactly 1 argument, " + args.length + " given", null);
            }
            DateTimeZoneState state = requireInitializedZone(self);
            if (!(args[0] instanceof PhpObject dtObj) || !(dtObj.nativeState() instanceof DateTimeState dtState)) {
                throw new PhpError(PhpError.TYPE_ERROR,
                        "DateTimeZone::getOffset(): Argument #1 ($datetime) must be of type DateTimeInterface", null);
            }
            if (dtState.time == null) {
                throw throwDateError(ctx, "DateObjectError", "Object of type " + dtObj.phpClass().name() + " has not been correctly initialized by calling parent::__construct() in its constructor");
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

        addMethod(language, methods, "listIdentifiers", true, (ctx, self, args) -> {
            if (args.length > 2) {
                throw new PhpError(PhpError.ARGUMENT_COUNT,
                        "DateTimeZone::listIdentifiers() expects at most 2 arguments, " + args.length + " given", null);
            }
            long group = args.length >= 1 ? PhpOps.toLong(args[0]) : GROUP_ALL;
            return listIdentifiers(group);
        });

        addMethod(language, methods, "getLocation", false, (ctx, self, args) -> {
            DateTimeZoneState state = requireInitializedZone(self);
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

        addMethod(language, methods, "getTransitions", false, (ctx, self, args) -> {
            DateTimeZoneState state = requireInitializedZone(self);
            if (state.type != TIMELIB_ZONETYPE_ID || state.tzInfo == null) {
                return false;
            }
            long begin = args.length >= 1 ? PhpOps.toLong(args[0]) : Long.MIN_VALUE;
            long end = args.length >= 2 ? PhpOps.toLong(args[1]) : Integer.MAX_VALUE;
            return buildTransitions(state.tzInfo, begin, end);
        });

        addMethod(language, methods, "listAbbreviations", true, (ctx, self, args) -> buildAbbreviationsList());

        addMethod(language, methods, "__serialize", false, (ctx, self, args) -> {
            DateTimeZoneState state = requireInitializedZone(self);
            PhpArray out = new PhpArray();
            out.put("timezone_type", (long) state.type);
            out.put("timezone", state.name());
            appendDynamicProperties(self, out);
            return out;
        });

        addMethod(language, methods, "__unserialize", false, (ctx, self, args) -> {
            if (args.length != 1 || !(args[0] instanceof PhpArray data)) {
                throw new PhpError(PhpError.TYPE_ERROR,
                        "DateTimeZone::__unserialize(): Argument #1 ($data) must be of type array", null);
            }
            Object tzVal = data.get("timezone");
            if (tzVal == null || tzVal == PhpNull.INSTANCE) {
                throw throwDateException(ctx, "Exception", "Invalid serialization data for DateTimeZone object");
            }
            DateTimeZoneState state = new DateTimeZoneState();
            String[] errMsg = new String[1];
            if (!timezoneInitialize(state, PhpOps.toStr(tzVal), errMsg)) {
                throw throwDateException(ctx, "Exception", "Invalid serialization data for DateTimeZone object");
            }
            self.setNativeState(state);
            applyDynamicProperties(self, data, java.util.Set.of("timezone_type", "timezone"));
            return PhpNull.INSTANCE;
        });

        addMethod(language, methods, "__wakeup", false, (ctx, self, args) -> PhpNull.INSTANCE);

        addMethod(language, methods, "__set_state", true, (ctx, self, args) -> {
            if (args.length != 1 || !(args[0] instanceof PhpArray data)) {
                throw new PhpError(PhpError.TYPE_ERROR,
                        "DateTimeZone::__set_state(): Argument #1 ($array) must be of type array", null);
            }
            Object tzVal = data.get("timezone");
            if (tzVal == null || tzVal == PhpNull.INSTANCE) {
                throw throwDateException(ctx, "Exception", "Invalid serialization data for DateTimeZone object");
            }
            PhpClass cls = ctx.lookupClass("DateTimeZone");
            PhpObject obj = new PhpObject(cls, ctx.nextObjectId());
            DateTimeZoneState state = new DateTimeZoneState();
            String[] errMsg = new String[1];
            if (!timezoneInitialize(state, PhpOps.toStr(tzVal), errMsg)) {
                throw throwDateException(ctx, "DateInvalidTimeZoneException",
                        "DateTimeZone::__set_state(): " + errMsg[0]);
            }
            obj.setNativeState(state);
            return obj;
        });

        return new PhpClassTemplate(
                language.nextTemplateId(), "DateTimeZone", false, false, false, false,
                null, List.of(), List.of(), constants, methods);
    }

    private static DateTimeZoneState requireInitializedZone(PhpObject self) {
        if (self.nativeState() instanceof DateTimeZoneState state && state.initialized) {
            return state;
        }
        throw new PhpError("Object of type " + self.phpClass().name()
                + " has not been correctly initialized by calling parent::__construct() in its constructor", null);
    }

    private static DateIntervalState requireInitializedInterval(PhpObject self) {
        if (self.nativeState() instanceof DateIntervalState state && state.initialized) {
            return state;
        }
        throw new PhpError("Object of type " + self.phpClass().name()
                + " has not been correctly initialized by calling parent::__construct() in its constructor", null);
    }

    private static PhpClassTemplate buildDateIntervalClass(PhpLanguage language) {
        List<PhpClassTemplate.PropertyDecl> props = List.of(
                new PhpClassTemplate.PropertyDecl("y", Visibility.PUBLIC, false, 0L, null),
                new PhpClassTemplate.PropertyDecl("m", Visibility.PUBLIC, false, 0L, null),
                new PhpClassTemplate.PropertyDecl("d", Visibility.PUBLIC, false, 0L, null),
                new PhpClassTemplate.PropertyDecl("h", Visibility.PUBLIC, false, 0L, null),
                new PhpClassTemplate.PropertyDecl("i", Visibility.PUBLIC, false, 0L, null),
                new PhpClassTemplate.PropertyDecl("s", Visibility.PUBLIC, false, 0L, null),
                new PhpClassTemplate.PropertyDecl("f", Visibility.PUBLIC, false, 0.0, null),
                new PhpClassTemplate.PropertyDecl("invert", Visibility.PUBLIC, false, 0L, null),
                new PhpClassTemplate.PropertyDecl("days", Visibility.PUBLIC, false, false, null),
                new PhpClassTemplate.PropertyDecl("from_string", Visibility.PUBLIC, false, false, null)
        );

        Map<String, PhpClassTemplate.MethodDecl> methods = new LinkedHashMap<>();

        addMethod(language, methods, "__construct", false, (ctx, self, args) -> {
            if (args.length != 1) {
                throw new PhpError(PhpError.ARGUMENT_COUNT,
                        "DateInterval::__construct() expects exactly 1 argument, " + args.length + " given", null);
            }
            String duration = PhpOps.toStr(args[0]);
            if (duration.indexOf('\0') >= 0) {
                throw new PhpError(PhpError.VALUE_ERROR,
                        "DateInterval::__construct(): Argument #1 ($duration) must not contain any null bytes", null);
            }
            TimelibErrorContainer[] errors = new TimelibErrorContainer[1];
            ParseIsoIntervals.IsoIntervalResult res = ParseIsoIntervals.strToInterval(duration, errors);
            if ((errors[0] != null && errors[0].errorCount > 0) || res.period == null) {
                throw throwDateException(ctx, "DateMalformedIntervalStringException",
                        "Unknown or bad format (" + duration + ")");
            }
            DateIntervalState state = new DateIntervalState(res.period);
            state.civilOrWall = 0; // PHP_DATE_WALL
            state.initialized = true;
            self.setNativeState(state);
            syncPropertiesFromRelTime(self, res.period, false);
            return PhpNull.INSTANCE;
        });

        addMethod(language, methods, "createFromDateString", true, (ctx, self, args) -> {
            if (args.length != 1) {
                throw new PhpError(PhpError.ARGUMENT_COUNT,
                        "DateInterval::createFromDateString() expects exactly 1 argument, " + args.length + " given", null);
            }
            String datetime = PhpOps.toStr(args[0]);
            if (datetime.indexOf('\0') >= 0) {
                throw new PhpError(PhpError.VALUE_ERROR,
                        "DateInterval::createFromDateString(): Argument #1 ($datetime) must not contain any null bytes", null);
            }
            TimelibErrorContainer[] err = new TimelibErrorContainer[1];
            TimelibTime time = ParseDate.strtotime(datetime, err, ZoneinfoDb.instance());
            if (err[0] != null && err[0].errorCount > 0) {
                TimelibErrorMessage first = err[0].errorMessages.get(0);
                char ch = first.character != 0 ? first.character : ' ';
                throw throwDateException(ctx, "DateMalformedIntervalStringException",
                        String.format("Unknown or bad format (%s) at position %d (%c): %s",
                                datetime, first.position, ch, first.message));
            }
            if (time.haveDate != 0 || time.haveTime != 0 || time.haveZone != 0) {
                throw throwDateException(ctx, "DateMalformedIntervalStringException",
                        "String '" + datetime + "' contains non-relative elements");
            }
            PhpClass cls = ctx.lookupClass("DateInterval");
            PhpObject obj = new PhpObject(cls, ctx.nextObjectId());
            cls.populateDefaults(obj);
            DateIntervalState state = new DateIntervalState(time.relative.copy());
            state.civilOrWall = 1; // PHP_DATE_CIVIL
            state.fromString = true;
            state.dateString = datetime;
            state.initialized = true;
            obj.setNativeState(state);
            syncPropertiesFromRelTime(obj, time.relative, true);
            return obj;
        });

        addMethod(language, methods, "format", false, (ctx, self, args) -> {
            if (args.length != 1) {
                throw new PhpError(PhpError.ARGUMENT_COUNT,
                        "DateInterval::format() expects exactly 1 argument, " + args.length + " given", null);
            }
            DateIntervalState state = requireInitializedInterval(self);
            syncRelTimeFromProperties(self, state.diff);
            String fmt = PhpOps.toStr(args[0]);
            return Interval.format(fmt, state.diff);
        });

        addMethod(language, methods, "__serialize", false, (ctx, self, args) -> {
            DateIntervalState state = requireInitializedInterval(self);
            syncRelTimeFromProperties(self, state.diff);
            PhpArray out = new PhpArray();
            if (state.fromString) {
                out.put("from_string", true);
                if (state.dateString != null) {
                    out.put("date_string", state.dateString);
                }
                appendDynamicProperties(self, out, DATEINTERVAL_PROPERTIES);
                return out;
            }
            out.put("y", state.diff.y);
            out.put("m", state.diff.m);
            out.put("d", state.diff.d);
            out.put("h", state.diff.h);
            out.put("i", state.diff.i);
            out.put("s", state.diff.s);
            out.put("f", (double) state.diff.us / 1000000.0);
            out.put("invert", (long) state.diff.invert);
            out.put("days", state.diff.days != TIMELIB_UNSET ? (Object) state.diff.days : Boolean.FALSE);
            out.put("from_string", false);
            appendDynamicProperties(self, out, DATEINTERVAL_PROPERTIES);
            return out;
        });

        addMethod(language, methods, "__unserialize", false, (ctx, self, args) -> {
            if (args.length != 1 || !(args[0] instanceof PhpArray data)) {
                throw new PhpError(PhpError.TYPE_ERROR,
                        "DateInterval::__unserialize(): Argument #1 ($data) must be of type array", null);
            }
            Object dateStr = data.get("date_string");
            if (dateStr != null && dateStr != PhpNull.INSTANCE) {
                String dstr = PhpOps.toStr(dateStr);
                TimelibErrorContainer[] err = new TimelibErrorContainer[1];
                TimelibTime time = ParseDate.strtotime(dstr, err, ZoneinfoDb.instance());
                if (err[0] != null && err[0].errorCount > 0) {
                    TimelibErrorMessage first = err[0].errorMessages.get(0);
                    char ch = first.character != 0 ? first.character : ' ';
                    throw throwDateException(ctx, "DateMalformedIntervalStringException",
                            String.format("Unknown or bad format (%s) at position %d (%c) while unserializing: %s",
                                    dstr, first.position, ch, first.message));
                }
                DateIntervalState state = new DateIntervalState(time.relative.copy());
                state.civilOrWall = 1;
                state.fromString = true;
                state.dateString = dstr;
                state.initialized = true;
                self.setNativeState(state);
                syncPropertiesFromRelTime(self, time.relative, true);
                applyDynamicProperties(self, data, java.util.Set.of("from_string", "date_string"));
                return PhpNull.INSTANCE;
            }
            DateIntervalState state = new DateIntervalState(new TimelibRelTime());
            state.diff.y = PhpOps.toLong(data.get("y"));
            state.diff.m = PhpOps.toLong(data.get("m"));
            state.diff.d = PhpOps.toLong(data.get("d"));
            state.diff.h = PhpOps.toLong(data.get("h"));
            state.diff.i = PhpOps.toLong(data.get("i"));
            state.diff.s = PhpOps.toLong(data.get("s"));
            Object fVal = data.get("f");
            state.diff.us = (long) Math.round(PhpOps.toDouble(fVal) * 1000000.0);
            state.diff.invert = (int) PhpOps.toLong(data.get("invert"));
            Object daysVal = data.get("days");
            if (daysVal instanceof Boolean b && !b) {
                state.diff.days = TIMELIB_UNSET;
            } else {
                state.diff.days = PhpOps.toLong(daysVal);
            }
            state.initialized = true;
            self.setNativeState(state);
            syncPropertiesFromRelTime(self, state.diff, false);
            applyDynamicProperties(self, data, java.util.Set.of("y", "m", "d", "h", "i", "s", "f", "invert",
                    "days", "from_string", "date_string"));
            return PhpNull.INSTANCE;
        });

        addMethod(language, methods, "__wakeup", false, (ctx, self, args) -> PhpNull.INSTANCE);

        addMethod(language, methods, "__set_state", true, (ctx, self, args) -> {
            if (args.length != 1 || !(args[0] instanceof PhpArray data)) {
                throw new PhpError(PhpError.TYPE_ERROR,
                        "DateInterval::__set_state(): Argument #1 ($array) must be of type array", null);
            }
            PhpClass cls = ctx.lookupClass("DateInterval");
            PhpObject obj = new PhpObject(cls, ctx.nextObjectId());
            cls.populateDefaults(obj);
            DateIntervalState state = new DateIntervalState(new TimelibRelTime());
            state.diff.y = PhpOps.toLong(data.get("y"));
            state.diff.m = PhpOps.toLong(data.get("m"));
            state.diff.d = PhpOps.toLong(data.get("d"));
            state.diff.h = PhpOps.toLong(data.get("h"));
            state.diff.i = PhpOps.toLong(data.get("i"));
            state.diff.s = PhpOps.toLong(data.get("s"));
            Object fVal = data.get("f");
            state.diff.us = (long) Math.round(PhpOps.toDouble(fVal) * 1000000.0);
            state.diff.invert = (int) PhpOps.toLong(data.get("invert"));
            Object daysVal = data.get("days");
            if (daysVal instanceof Boolean b && !b) {
                state.diff.days = TIMELIB_UNSET;
            } else {
                state.diff.days = PhpOps.toLong(daysVal);
            }
            state.initialized = true;
            obj.setNativeState(state);
            syncPropertiesFromRelTime(obj, state.diff, false);
            return obj;
        });

        return new PhpClassTemplate(
                language.nextTemplateId(), "DateInterval", false, false, false, false,
                null, List.of(), props, List.of(), methods);
    }

    /** C: {@code timezone_initialize} */
    @TruffleBoundary
    public static boolean timezoneInitialize(DateTimeZoneState state, String tz, String[] warningMessage) {
        TimelibTime dummy = new TimelibTime();
        int[] dst = new int[1];
        int[] notFound = new int[1];
        ParseDate.Cursor ptr = new ParseDate.Cursor(tz);

        dummy.z = (int) ParseDate.timelibParseZone(ptr, dst, dummy, notFound, ZoneinfoDb.instance());
        if (dummy.z >= (100 * 60 * 60) || dummy.z <= (-100 * 60 * 60)) {
            if (warningMessage != null) {
                warningMessage[0] = "Timezone offset is out of range (" + tz + ")";
            }
            return false;
        }
        dummy.dst = dst[0];
        if (notFound[0] == 0 && ptr.ch() != '\0') {
            if (warningMessage != null) {
                warningMessage[0] = "Unknown or bad timezone (" + tz + ")";
            }
            return false;
        }
        if (notFound[0] != 0) {
            if (warningMessage != null) {
                warningMessage[0] = "Unknown or bad timezone (" + tz + ")";
            }
            return false;
        }
        state.type = dummy.zoneType;
        state.tzInfo = dummy.tzInfo;
        state.utcOffset = dummy.z;
        state.dst = dummy.dst;
        state.tzAbbr = dummy.tzAbbr;
        state.initialized = true;
        return true;
    }

    @TruffleBoundary
    private static PhpArray listIdentifiers(long group) {
        PhpArray arr = new PhpArray();
        for (String id : ZoneinfoDb.instance().identifiers()) {
            if (matchesGroup(id, group)) {
                arr.append(id);
            }
        }
        return arr;
    }

    public static PhpArray listIdentifiersPublic(long group) {
        return listIdentifiers(group);
    }

    private static boolean matchesGroup(String id, long group) {
        if (group == GROUP_ALL_WITH_BC || group == GROUP_ALL) {
            return true;
        }
        int slash = id.indexOf('/');
        String prefix = slash > 0 ? id.substring(0, slash) : id;
        return switch (prefix) {
            case "Africa" -> (group & GROUP_AFRICA) != 0;
            case "America" -> (group & GROUP_AMERICA) != 0;
            case "Antarctica" -> (group & GROUP_ANTARCTICA) != 0;
            case "Arctic" -> (group & GROUP_ARCTIC) != 0;
            case "Asia" -> (group & GROUP_ASIA) != 0;
            case "Atlantic" -> (group & GROUP_ATLANTIC) != 0;
            case "Australia" -> (group & GROUP_AUSTRALIA) != 0;
            case "Europe" -> (group & GROUP_EUROPE) != 0;
            case "Indian" -> (group & GROUP_INDIAN) != 0;
            case "Pacific" -> (group & GROUP_PACIFIC) != 0;
            case "UTC" -> (group & GROUP_UTC) != 0;
            default -> false;
        };
    }

    @TruffleBoundary
    public static PhpArray buildAbbreviationsList() {
        PhpArray res = new PhpArray();
        for (dev.phpruntime.timelib.TimezoneMap.Entry entry : dev.phpruntime.timelib.TimezoneMap.TIMEZONE_LOOKUP) {
            PhpArray element = new PhpArray();
            element.put("dst", entry.type != 0);
            element.put("offset", (long) entry.gmtoffset);
            if (entry.fullTzName != null) {
                element.put("timezone_id", entry.fullTzName);
            } else {
                element.put("timezone_id", PhpNull.INSTANCE);
            }
            Object existing = res.get(entry.name);
            PhpArray abbrArr;
            if (existing instanceof PhpArray a) {
                abbrArr = a;
            } else {
                abbrArr = new PhpArray();
                res.put(entry.name, abbrArr);
            }
            abbrArr.append(element);
        }
        return res;
    }

    @TruffleBoundary
    public static Object buildTransitions(TimelibTzinfo tz, long timestampBegin, long timestampEnd) {
        if (tz == null) return false;
        PhpArray out = new PhpArray();
        int timecnt = tz.bit64 != null ? (int) tz.bit64.timecnt : 0;
        int beginIdx = 0;
        boolean found = false;

        if (timestampBegin == Long.MIN_VALUE) {
            addTransitionNominal(out, tz, timestampBegin);
            beginIdx = 0;
            found = true;
        } else {
            beginIdx = 0;
            found = false;
            if (timecnt > 0) {
                do {
                    if (tz.trans[beginIdx] > timestampBegin) {
                        if (beginIdx > 0) {
                            addTransition(out, tz, beginIdx - 1, timestampBegin);
                        } else {
                            addTransitionNominal(out, tz, timestampBegin);
                        }
                        found = true;
                        break;
                    }
                    beginIdx++;
                } while (beginIdx < timecnt);
            }
        }

        if (!found) {
            if (timecnt > 0) {
                addTransition(out, tz, timecnt - 1, timestampBegin);
            } else {
                addTransitionNominal(out, tz, timestampBegin);
            }
        } else {
            for (int i = beginIdx; i < timecnt; i++) {
                if (tz.trans[i] < timestampEnd) {
                    addTransition(out, tz, i, tz.trans[i]);
                } else {
                    return out;
                }
            }
        }
        return out;
    }

    private static void addTransitionNominal(PhpArray out, TimelibTzinfo tz, long ts) {
        PhpArray elem = new PhpArray();
        elem.put("ts", ts);
        TimelibTime t = new TimelibTime();
        Unixtime2Tm.unixtime2gmt(t, ts);
        elem.put("time", DateFunctions.dateFormat("x-m-d\\TH:i:s+00:00", t, false));
        if (tz.type != null && tz.type.length > 0) {
            TimelibTzinfo.Ttinfo tt = tz.type[0];
            elem.put("offset", (long) tt.offset);
            elem.put("isdst", tt.isdst != 0);
            elem.put("abbr", tz.getAbbr(tt.abbrIdx));
        } else {
            elem.put("offset", 0L);
            elem.put("isdst", false);
            elem.put("abbr", "UTC");
        }
        out.append(elem);
    }

    private static void addTransition(PhpArray out, TimelibTzinfo tz, int i, long ts) {
        PhpArray elem = new PhpArray();
        elem.put("ts", ts);
        TimelibTime t = new TimelibTime();
        Unixtime2Tm.unixtime2gmt(t, ts);
        elem.put("time", DateFunctions.dateFormat("x-m-d\\TH:i:s+00:00", t, false));
        int transIdx = (tz.transIdx != null && i < tz.transIdx.length) ? tz.transIdx[i] : 0;
        TimelibTzinfo.Ttinfo tt = (tz.type != null && transIdx < tz.type.length) ? tz.type[transIdx] : null;
        if (tt != null) {
            elem.put("offset", (long) tt.offset);
            elem.put("isdst", tt.isdst != 0);
            elem.put("abbr", tz.getAbbr(tt.abbrIdx));
        } else {
            elem.put("offset", 0L);
            elem.put("isdst", false);
            elem.put("abbr", "UTC");
        }
        out.append(elem);
    }

    @TruffleBoundary
    public static TimelibTime dateInitialize(PhpContext ctx, String timeStr, String format,
                                             PhpObject tzObj, boolean isCtor, boolean isFormat) {
        if (timeStr == null || timeStr.isEmpty()) {
            if (format != null) {
                timeStr = "";
            } else {
                timeStr = "now";
            }
        }
        TimelibErrorContainer[] err = new TimelibErrorContainer[1];
        TimelibTime parsedTime;
        if (format != null) {
            parsedTime = ParseDate.parseFromFormat(format, timeStr, err, ZoneinfoDb.instance());
        } else {
            parsedTime = ParseDate.strtotime(timeStr, err, ZoneinfoDb.instance());
        }
        ContextState.of(ctx).lastDateErrors = err[0];

        if (isCtor && err[0] != null && err[0].errorCount > 0) {
            TimelibErrorMessage first = err[0].errorMessages.get(0);
            char ch = first.character != 0 ? first.character : ' ';
            throw throwDateException(ctx, "DateMalformedStringException",
                    String.format("Failed to parse time string (%s) at position %d (%c): %s",
                            timeStr, first.position, ch, first.message));
        }
        if (err[0] != null && err[0].errorCount > 0) {
            return null;
        }

        TimelibTzinfo tzi = null;
        int type = TIMELIB_ZONETYPE_ID;
        long newOffset = 0;
        int newDst = 0;
        String newAbbr = null;

        if (tzObj != null && tzObj.nativeState() instanceof DateTimeZoneState tzState) {
            if (!tzState.initialized) {
                throw throwDateError(ctx, "DateError", "The DateTimeZone object has not been correctly initialized by its constructor");
            }
            type = tzState.type;
            switch (type) {
                case TIMELIB_ZONETYPE_ID -> tzi = tzState.tzInfo;
                case TIMELIB_ZONETYPE_OFFSET -> newOffset = tzState.utcOffset;
                case TIMELIB_ZONETYPE_ABBR -> {
                    newOffset = tzState.utcOffset;
                    newDst = tzState.dst;
                    newAbbr = tzState.tzAbbr;
                }
            }
        } else if (parsedTime.tzInfo != null) {
            tzi = parsedTime.tzInfo;
            type = TIMELIB_ZONETYPE_ID;
        } else {
            tzi = DateFunctions.getTimezoneInfo(ctx);
            type = TIMELIB_ZONETYPE_ID;
        }

        TimelibTime now = new TimelibTime();
        now.zoneType = type;
        switch (type) {
            case TIMELIB_ZONETYPE_ID -> now.tzInfo = tzi;
            case TIMELIB_ZONETYPE_OFFSET -> now.z = (int) newOffset;
            case TIMELIB_ZONETYPE_ABBR -> {
                now.z = (int) newOffset;
                now.dst = newDst;
                now.tzAbbr = newAbbr;
            }
        }
        long sec = System.currentTimeMillis() / 1000L;
        long usec = (System.currentTimeMillis() % 1000L) * 1000L;
        Unixtime2Tm.unixtime2local(now, sec);
        now.us = usec;

        if (format == null && timeStr.equals("now")) {
            return now;
        }

        int options = TIMELIB_NO_CLONE;
        if (isFormat) {
            options |= TIMELIB_OVERRIDE_TIME;
        }
        ParseDate.fillHoles(parsedTime, now, options);

        Tm2Unixtime.updateTs(parsedTime, tzi);
        Unixtime2Tm.updateFromSse(parsedTime);

        parsedTime.haveRelative = 0;
        return parsedTime;
    }

    private static DateTimeState requireInitializedDateTime(PhpObject self) {
        if (self.nativeState() instanceof DateTimeState state && state.time != null) {
            return state;
        }
        throw new PhpError("Object of type " + self.phpClass().name()
                + " has not been correctly initialized by calling parent::__construct() in its constructor", null);
    }

    @TruffleBoundary
    public static boolean phpDateModify(PhpContext ctx, PhpObject object, String modify, boolean throwException) {
        DateTimeState dateState = requireInitializedDateTime(object);
        TimelibErrorContainer[] err = new TimelibErrorContainer[1];
        TimelibTime tmpTime = ParseDate.strtotime(modify, err, ZoneinfoDb.instance());
        if (err[0] != null && err[0].errorCount > 0) {
            TimelibErrorMessage first = err[0].errorMessages.get(0);
            char ch = first.character != 0 ? first.character : ' ';
            if (throwException) {
                throw throwDateException(ctx, "DateMalformedStringException",
                        String.format("%s::modify(): Failed to parse time string (%s) at position %d (%c): %s",
                                object.phpClass().name(), modify, first.position, ch, first.message));
            } else {
                ctx.warning(String.format("Failed to parse time string (%s) at position %d (%c): %s",
                        modify, first.position, ch, first.message), 0);
                return false;
            }
        }

        dateState.time.relative = tmpTime.relative.copy();
        dateState.time.haveRelative = tmpTime.haveRelative;
        dateState.time.sseUptodate = 0;

        if (tmpTime.y != TIMELIB_UNSET) {
            dateState.time.y = tmpTime.y;
        }
        if (tmpTime.m != TIMELIB_UNSET) {
            dateState.time.m = tmpTime.m;
        }
        if (tmpTime.d != TIMELIB_UNSET) {
            dateState.time.d = tmpTime.d;
        }

        if (tmpTime.h != TIMELIB_UNSET) {
            dateState.time.h = tmpTime.h;
            if (tmpTime.i != TIMELIB_UNSET) {
                dateState.time.i = tmpTime.i;
                if (tmpTime.s != TIMELIB_UNSET) {
                    dateState.time.s = tmpTime.s;
                } else {
                    dateState.time.s = 0;
                }
            } else {
                dateState.time.i = 0;
                dateState.time.s = 0;
            }
        }

        if (tmpTime.us != TIMELIB_UNSET) {
            dateState.time.us = tmpTime.us;
        }

        if (tmpTime.y == 1970 && tmpTime.m == 1 && tmpTime.d == 1 &&
                tmpTime.h == 0 && tmpTime.i == 0 && tmpTime.s == 0 && tmpTime.us == 0 &&
                tmpTime.haveZone != 0 && tmpTime.zoneType == TIMELIB_ZONETYPE_OFFSET &&
                tmpTime.z == 0 && tmpTime.dst == 0) {
            Unixtime2Tm.setTimezoneFromOffset(dateState.time, 0);
        }

        Tm2Unixtime.updateTs(dateState.time, dateState.time.tzInfo);
        Unixtime2Tm.updateFromSse(dateState.time);
        dateState.time.haveRelative = 0;
        dateState.time.relative = new TimelibRelTime();

        return true;
    }

    public static void phpDateAdd(PhpObject self, PhpObject intervalObj) {
        DateTimeState dtState = requireInitializedDateTime(self);
        DateIntervalState intState = requireInitializedInterval(intervalObj);
        syncRelTimeFromProperties(intervalObj, intState.diff);
        TimelibTime newTime;
        if (intState.civilOrWall == 0) { // PHP_DATE_WALL
            newTime = Interval.addWall(dtState.time, intState.diff);
        } else {
            newTime = Interval.add(dtState.time, intState.diff);
        }
        dtState.time = newTime;
    }

    public static void phpDateSub(PhpObject self, PhpObject intervalObj) {
        DateTimeState dtState = requireInitializedDateTime(self);
        DateIntervalState intState = requireInitializedInterval(intervalObj);
        syncRelTimeFromProperties(intervalObj, intState.diff);
        TimelibTime newTime;
        if (intState.civilOrWall == 0) { // PHP_DATE_WALL
            newTime = Interval.subWall(dtState.time, intState.diff);
        } else {
            newTime = Interval.sub(dtState.time, intState.diff);
        }
        dtState.time = newTime;
    }

    @TruffleBoundary
    public static PhpObject phpDateDiff(PhpContext ctx, PhpObject self, Object target, boolean absolute) {
        DateTimeState dtState1 = requireInitializedDateTime(self);
        if (!(target instanceof PhpObject targetObj) || !(targetObj.nativeState() instanceof DateTimeState dtState2)) {
            throw new PhpError(PhpError.TYPE_ERROR,
                    self.phpClass().name() + "::diff(): Argument #1 ($targetObject) must be of type DateTimeInterface", null);
        }
        if (dtState2.time == null) {
            throw throwDateError(ctx, "DateObjectError", "Object of type " + targetObj.phpClass().name()
                    + " has not been correctly initialized by calling parent::__construct() in its constructor");
        }
        TimelibRelTime diff = Interval.diff(dtState1.time, dtState2.time);
        if (absolute) {
            diff.invert = 0;
        }
        PhpClass cls = ctx.lookupClass("DateInterval");
        PhpObject obj = new PhpObject(cls, ctx.nextObjectId());
        cls.populateDefaults(obj);
        DateIntervalState intState = new DateIntervalState(diff);
        intState.civilOrWall = 1; // PHP_DATE_CIVIL
        intState.initialized = true;
        obj.setNativeState(intState);
        syncPropertiesFromRelTime(obj, diff, false);
        return obj;
    }

    public static void phpDateTimezoneSet(PhpObject self, PhpObject tzObj) {
        DateTimeState dtState = requireInitializedDateTime(self);
        DateTimeZoneState tzState = requireInitializedZone(tzObj);
        switch (tzState.type) {
            case TIMELIB_ZONETYPE_OFFSET -> Unixtime2Tm.setTimezoneFromOffset(dtState.time, tzState.utcOffset);
            case TIMELIB_ZONETYPE_ABBR -> Unixtime2Tm.setTimezoneFromAbbr(dtState.time, tzState.tzAbbr, tzState.utcOffset, tzState.dst);
            case TIMELIB_ZONETYPE_ID -> Unixtime2Tm.setTimezone(dtState.time, tzState.tzInfo);
        }
        Unixtime2Tm.unixtime2local(dtState.time, dtState.time.sse);
    }

    public static Object phpDateTimezoneGet(PhpContext ctx, PhpObject self) {
        DateTimeState dtState = requireInitializedDateTime(self);
        if (dtState.time.isLocaltime == 0) {
            return false;
        }
        PhpClass cls = ctx.lookupClass("DateTimeZone");
        PhpObject obj = new PhpObject(cls, ctx.nextObjectId());
        DateTimeZoneState tzState = new DateTimeZoneState();
        tzState.type = dtState.time.zoneType;
        tzState.initialized = true;
        switch (dtState.time.zoneType) {
            case TIMELIB_ZONETYPE_ID -> tzState.tzInfo = dtState.time.tzInfo;
            case TIMELIB_ZONETYPE_OFFSET -> tzState.utcOffset = (int) dtState.time.z;
            case TIMELIB_ZONETYPE_ABBR -> {
                tzState.utcOffset = (int) dtState.time.z;
                tzState.dst = dtState.time.dst;
                tzState.tzAbbr = dtState.time.tzAbbr;
            }
        }
        obj.setNativeState(tzState);
        return obj;
    }

    public static void phpDateSetDate(PhpObject self, long y, long m, long d) {
        DateTimeState dtState = requireInitializedDateTime(self);
        dtState.time.y = y;
        dtState.time.m = m;
        dtState.time.d = d;
        Tm2Unixtime.updateTs(dtState.time, dtState.time.tzInfo);
        Unixtime2Tm.updateFromSse(dtState.time);
    }

    public static void phpDateSetTime(PhpObject self, long h, long i, long s, long us) {
        DateTimeState dtState = requireInitializedDateTime(self);
        dtState.time.h = h;
        dtState.time.i = i;
        dtState.time.s = s;
        dtState.time.us = us;
        Tm2Unixtime.updateTs(dtState.time, dtState.time.tzInfo);
        Unixtime2Tm.updateFromSse(dtState.time);
    }

    public static void phpDateSetIsoDate(PhpObject self, long y, long w, long d) {
        DateTimeState dtState = requireInitializedDateTime(self);
        long[] year = new long[1];
        long[] month = new long[1];
        long[] day = new long[1];
        Dow.dateFromIsodate(y, w, d, year, month, day);
        dtState.time.y = year[0];
        dtState.time.m = month[0];
        dtState.time.d = day[0];
        Tm2Unixtime.updateTs(dtState.time, dtState.time.tzInfo);
        Unixtime2Tm.updateFromSse(dtState.time);
    }

    public static void phpDateSetTimestamp(PhpObject self, long ts) {
        DateTimeState dtState = requireInitializedDateTime(self);
        if (dtState.time.zoneType == TIMELIB_ZONETYPE_ID && dtState.time.tzInfo != null) {
            Unixtime2Tm.unixtime2local(dtState.time, ts);
        } else {
            Unixtime2Tm.unixtime2gmt(dtState.time, ts);
        }
        dtState.time.sse = ts;
        Tm2Unixtime.updateTs(dtState.time, dtState.time.tzInfo);
        Unixtime2Tm.updateFromSse(dtState.time);
    }

    private static PhpClassTemplate buildDateTimeClass(PhpLanguage language, boolean isImmutable) {
        String className = isImmutable ? "DateTimeImmutable" : "DateTime";
        Map<String, PhpClassTemplate.MethodDecl> methods = new LinkedHashMap<>();

        addMethod(language, methods, "__construct", false, (ctx, self, args) -> {
            String timeStr = args.length >= 1 && args[0] != PhpNull.INSTANCE ? PhpOps.toStr(args[0]) : "now";
            PhpObject tzObj = null;
            if (args.length >= 2 && args[1] != PhpNull.INSTANCE) {
                if (!(args[1] instanceof PhpObject o) || !(o.nativeState() instanceof DateTimeZoneState)) {
                    throw new PhpError(PhpError.TYPE_ERROR,
                            className + "::__construct(): Argument #2 ($timezone) must be of type ?DateTimeZone, " +
                                    PhpOps.typeName(args[1]) + " given", null);
                }
                tzObj = (PhpObject) args[1];
            }
            TimelibTime time = dateInitialize(ctx, timeStr, null, tzObj, true, false);
            self.setNativeState(new DateTimeState(time));
            return PhpNull.INSTANCE;
        });

        addMethod(language, methods, "format", false, (ctx, self, args) -> {
            if (args.length != 1) {
                throw new PhpError(PhpError.ARGUMENT_COUNT,
                        className + "::format() expects exactly 1 argument, " + args.length + " given", null);
            }
            DateTimeState state = requireInitializedDateTime(self);
            String format = PhpOps.toStr(args[0]);
            return DateFunctions.dateFormat(format, state.time, state.time.isLocaltime != 0);
        });

        addMethod(language, methods, "diff", false, (ctx, self, args) -> {
            if (args.length < 1) {
                throw new PhpError(PhpError.ARGUMENT_COUNT,
                        className + "::diff() expects at least 1 argument, " + args.length + " given", null);
            }
            boolean absolute = args.length >= 2 && PhpOps.toBool(args[1]);
            return phpDateDiff(ctx, self, args[0], absolute);
        });

        addMethod(language, methods, "modify", false, (ctx, self, args) -> {
            if (args.length != 1) {
                throw new PhpError(PhpError.ARGUMENT_COUNT,
                        className + "::modify() expects exactly 1 argument, " + args.length + " given", null);
            }
            String modifier = PhpOps.toStr(args[0]);
            if (isImmutable) {
                DateTimeState state = requireInitializedDateTime(self);
                PhpClass cls = self.phpClass();
                PhpObject clone = new PhpObject(cls, ctx.nextObjectId());
                clone.setNativeState(state.copy());
                if (!phpDateModify(ctx, clone, modifier, true)) {
                    return false;
                }
                return clone;
            } else {
                if (!phpDateModify(ctx, self, modifier, true)) {
                    return false;
                }
                return self;
            }
        });

        addMethod(language, methods, "add", false, (ctx, self, args) -> {
            if (args.length != 1) {
                throw new PhpError(PhpError.ARGUMENT_COUNT,
                        className + "::add() expects exactly 1 argument, " + args.length + " given", null);
            }
            if (!(args[0] instanceof PhpObject intObj) || !(intObj.nativeState() instanceof DateIntervalState)) {
                throw new PhpError(PhpError.TYPE_ERROR,
                        className + "::add(): Argument #1 ($interval) must be of type DateInterval", null);
            }
            if (isImmutable) {
                DateTimeState state = requireInitializedDateTime(self);
                PhpClass cls = self.phpClass();
                PhpObject clone = new PhpObject(cls, ctx.nextObjectId());
                clone.setNativeState(state.copy());
                phpDateAdd(clone, intObj);
                return clone;
            } else {
                phpDateAdd(self, intObj);
                return self;
            }
        });

        addMethod(language, methods, "sub", false, (ctx, self, args) -> {
            if (args.length != 1) {
                throw new PhpError(PhpError.ARGUMENT_COUNT,
                        className + "::sub() expects exactly 1 argument, " + args.length + " given", null);
            }
            if (!(args[0] instanceof PhpObject intObj) || !(intObj.nativeState() instanceof DateIntervalState)) {
                throw new PhpError(PhpError.TYPE_ERROR,
                        className + "::sub(): Argument #1 ($interval) must be of type DateInterval", null);
            }
            if (isImmutable) {
                DateTimeState state = requireInitializedDateTime(self);
                PhpClass cls = self.phpClass();
                PhpObject clone = new PhpObject(cls, ctx.nextObjectId());
                clone.setNativeState(state.copy());
                phpDateSub(clone, intObj);
                return clone;
            } else {
                phpDateSub(self, intObj);
                return self;
            }
        });

        addMethod(language, methods, "getTimezone", false, (ctx, self, args) -> phpDateTimezoneGet(ctx, self));

        addMethod(language, methods, "setTimezone", false, (ctx, self, args) -> {
            if (args.length != 1) {
                throw new PhpError(PhpError.ARGUMENT_COUNT,
                        className + "::setTimezone() expects exactly 1 argument, " + args.length + " given", null);
            }
            if (!(args[0] instanceof PhpObject tzObj) || !(tzObj.nativeState() instanceof DateTimeZoneState)) {
                throw new PhpError(PhpError.TYPE_ERROR,
                        className + "::setTimezone(): Argument #1 ($timezone) must be of type DateTimeZone", null);
            }
            if (isImmutable) {
                DateTimeState state = requireInitializedDateTime(self);
                PhpClass cls = self.phpClass();
                PhpObject clone = new PhpObject(cls, ctx.nextObjectId());
                clone.setNativeState(state.copy());
                phpDateTimezoneSet(clone, tzObj);
                return clone;
            } else {
                phpDateTimezoneSet(self, tzObj);
                return self;
            }
        });

        addMethod(language, methods, "getOffset", false, (ctx, self, args) -> {
            DateTimeState state = requireInitializedDateTime(self);
            return (long) state.time.z;
        });

        addMethod(language, methods, "getTimestamp", false, (ctx, self, args) -> {
            DateTimeState state = requireInitializedDateTime(self);
            return state.time.sse;
        });

        addMethod(language, methods, "getMicrosecond", false, (ctx, self, args) -> {
            DateTimeState state = requireInitializedDateTime(self);
            return state.time.us;
        });

        addMethod(language, methods, "setTimestamp", false, (ctx, self, args) -> {
            if (args.length != 1) {
                throw new PhpError(PhpError.ARGUMENT_COUNT,
                        className + "::setTimestamp() expects exactly 1 argument, " + args.length + " given", null);
            }
            long ts = PhpOps.toLong(args[0]);
            if (isImmutable) {
                DateTimeState state = requireInitializedDateTime(self);
                PhpClass cls = self.phpClass();
                PhpObject clone = new PhpObject(cls, ctx.nextObjectId());
                clone.setNativeState(state.copy());
                phpDateSetTimestamp(clone, ts);
                return clone;
            } else {
                phpDateSetTimestamp(self, ts);
                return self;
            }
        });

        addMethod(language, methods, "setMicrosecond", false, (ctx, self, args) -> {
            if (args.length != 1) {
                throw new PhpError(PhpError.ARGUMENT_COUNT,
                        className + "::setMicrosecond() expects exactly 1 argument, " + args.length + " given", null);
            }
            long us = PhpOps.toLong(args[0]);
            if (isImmutable) {
                DateTimeState state = requireInitializedDateTime(self);
                PhpClass cls = self.phpClass();
                PhpObject clone = new PhpObject(cls, ctx.nextObjectId());
                clone.setNativeState(state.copy());
                DateTimeState cState = (DateTimeState) clone.nativeState();
                cState.time.us = us;
                return clone;
            } else {
                DateTimeState state = requireInitializedDateTime(self);
                state.time.us = us;
                return self;
            }
        });

        addMethod(language, methods, "setDate", false, (ctx, self, args) -> {
            if (args.length != 3) {
                throw new PhpError(PhpError.ARGUMENT_COUNT,
                        className + "::setDate() expects exactly 3 arguments, " + args.length + " given", null);
            }
            long y = PhpOps.toLong(args[0]);
            long m = PhpOps.toLong(args[1]);
            long d = PhpOps.toLong(args[2]);
            if (isImmutable) {
                DateTimeState state = requireInitializedDateTime(self);
                PhpClass cls = self.phpClass();
                PhpObject clone = new PhpObject(cls, ctx.nextObjectId());
                clone.setNativeState(state.copy());
                phpDateSetDate(clone, y, m, d);
                return clone;
            } else {
                phpDateSetDate(self, y, m, d);
                return self;
            }
        });

        addMethod(language, methods, "setTime", false, (ctx, self, args) -> {
            if (args.length < 2) {
                throw new PhpError(PhpError.ARGUMENT_COUNT,
                        className + "::setTime() expects at least 2 arguments, " + args.length + " given", null);
            }
            long h = PhpOps.toLong(args[0]);
            long i = PhpOps.toLong(args[1]);
            long s = args.length >= 3 ? PhpOps.toLong(args[2]) : 0L;
            long us = args.length >= 4 ? PhpOps.toLong(args[3]) : 0L;
            if (isImmutable) {
                DateTimeState state = requireInitializedDateTime(self);
                PhpClass cls = self.phpClass();
                PhpObject clone = new PhpObject(cls, ctx.nextObjectId());
                clone.setNativeState(state.copy());
                phpDateSetTime(clone, h, i, s, us);
                return clone;
            } else {
                phpDateSetTime(self, h, i, s, us);
                return self;
            }
        });

        addMethod(language, methods, "setISODate", false, (ctx, self, args) -> {
            if (args.length < 2) {
                throw new PhpError(PhpError.ARGUMENT_COUNT,
                        className + "::setISODate() expects at least 2 arguments, " + args.length + " given", null);
            }
            long y = PhpOps.toLong(args[0]);
            long w = PhpOps.toLong(args[1]);
            long d = args.length >= 3 ? PhpOps.toLong(args[2]) : 1L;
            if (isImmutable) {
                DateTimeState state = requireInitializedDateTime(self);
                PhpClass cls = self.phpClass();
                PhpObject clone = new PhpObject(cls, ctx.nextObjectId());
                clone.setNativeState(state.copy());
                phpDateSetIsoDate(clone, y, w, d);
                return clone;
            } else {
                phpDateSetIsoDate(self, y, w, d);
                return self;
            }
        });

        addMethod(language, methods, "createFromFormat", true, (ctx, self, args) -> {
            if (args.length < 2) {
                throw new PhpError(PhpError.ARGUMENT_COUNT,
                        className + "::createFromFormat() expects at least 2 arguments, " + args.length + " given", null);
            }
            String fmt = PhpOps.toStr(args[0]);
            String dt = PhpOps.toStr(args[1]);
            PhpObject tzObj = null;
            if (args.length >= 3 && args[2] != PhpNull.INSTANCE) {
                if (args[2] instanceof PhpObject o && o.nativeState() instanceof DateTimeZoneState) {
                    tzObj = o;
                }
            }
            TimelibTime time = dateInitialize(ctx, dt, fmt, tzObj, false, true);
            if (time == null) {
                return false;
            }
            PhpClass cls = ctx.lookupClass(className);
            PhpObject obj = new PhpObject(cls, ctx.nextObjectId());
            obj.setNativeState(new DateTimeState(time));
            return obj;
        });

        addMethod(language, methods, "createFromImmutable", true, (ctx, self, args) -> {
            if (args.length != 1 || !(args[0] instanceof PhpObject immObj) ||
                    !(immObj.nativeState() instanceof DateTimeState immState) || immState.time == null) {
                throw new PhpError(PhpError.TYPE_ERROR,
                        className + "::createFromImmutable(): Argument #1 ($object) must be of type DateTimeImmutable", null);
            }
            PhpClass cls = ctx.lookupClass(className);
            PhpObject obj = new PhpObject(cls, ctx.nextObjectId());
            obj.setNativeState(immState.copy());
            return obj;
        });

        addMethod(language, methods, "createFromMutable", true, (ctx, self, args) -> {
            if (args.length != 1 || !(args[0] instanceof PhpObject mutObj) ||
                    !(mutObj.nativeState() instanceof DateTimeState mutState) || mutState.time == null) {
                throw new PhpError(PhpError.TYPE_ERROR,
                        className + "::createFromMutable(): Argument #1 ($object) must be of type DateTime", null);
            }
            PhpClass cls = ctx.lookupClass(className);
            PhpObject obj = new PhpObject(cls, ctx.nextObjectId());
            obj.setNativeState(mutState.copy());
            return obj;
        });

        addMethod(language, methods, "createFromInterface", true, (ctx, self, args) -> {
            if (args.length != 1 || !(args[0] instanceof PhpObject dtiObj) ||
                    !(dtiObj.nativeState() instanceof DateTimeState dtiState) || dtiState.time == null) {
                throw new PhpError(PhpError.TYPE_ERROR,
                        className + "::createFromInterface(): Argument #1 ($object) must be of type DateTimeInterface", null);
            }
            PhpClass cls = ctx.lookupClass(className);
            PhpObject obj = new PhpObject(cls, ctx.nextObjectId());
            obj.setNativeState(dtiState.copy());
            return obj;
        });

        addMethod(language, methods, "createFromTimestamp", true, (ctx, self, args) -> {
            if (args.length != 1) {
                throw new PhpError(PhpError.ARGUMENT_COUNT,
                        className + "::createFromTimestamp() expects exactly 1 argument, " + args.length + " given", null);
            }
            long ts;
            long us = 0;
            if (args[0] instanceof Double || args[0] instanceof Float) {
                double d = PhpOps.toDouble(args[0]);
                ts = (long) Math.floor(d);
                us = Math.round((d - ts) * 1000000.0);
            } else {
                ts = PhpOps.toLong(args[0]);
            }
            TimelibTime t = new TimelibTime();
            t.zoneType = TIMELIB_ZONETYPE_OFFSET;
            Unixtime2Tm.unixtime2gmt(t, ts);
            Tm2Unixtime.updateTs(t, null);
            t.us = us;
            PhpClass cls = ctx.lookupClass(className);
            PhpObject obj = new PhpObject(cls, ctx.nextObjectId());
            obj.setNativeState(new DateTimeState(t));
            return obj;
        });

        addMethod(language, methods, "getLastErrors", true, (ctx, self, args) ->
                DateFunctions.errorContainerToPhp(ContextState.of(ctx).lastDateErrors));

        addMethod(language, methods, "__serialize", false, (ctx, self, args) -> {
            DateTimeState state = requireInitializedDateTime(self);
            PhpArray out = new PhpArray();
            out.put("date", String.format("%04d-%02d-%02d %02d:%02d:%02d.%06d",
                    state.time.y, state.time.m, state.time.d, state.time.h, state.time.i, state.time.s, state.time.us));
            out.put("timezone_type", (long) state.time.zoneType);
            String tzName = switch (state.time.zoneType) {
                case TIMELIB_ZONETYPE_ID -> state.time.tzInfo != null ? state.time.tzInfo.name : "";
                case TIMELIB_ZONETYPE_OFFSET -> formatOffsetString(state.time.z);
                case TIMELIB_ZONETYPE_ABBR -> state.time.tzAbbr != null ? state.time.tzAbbr : "";
                default -> "";
            };
            out.put("timezone", tzName);
            appendDynamicProperties(self, out);
            return out;
        });

        addMethod(language, methods, "__unserialize", false, (ctx, self, args) -> {
            if (args.length != 1 || !(args[0] instanceof PhpArray data)) {
                throw new PhpError(PhpError.TYPE_ERROR,
                        className + "::__unserialize(): Argument #1 ($data) must be of type array", null);
            }
            Object dateVal = data.get("date");
            Object tzTypeVal = data.get("timezone_type");
            Object tzVal = data.get("timezone");
            if (dateVal == null || tzTypeVal == null || tzVal == null) {
                throw throwDateException(ctx, "DateException", "Invalid serialization data for " + className + " object");
            }
            String dstr = PhpOps.toStr(dateVal);
            long tzType = PhpOps.toLong(tzTypeVal);
            String tz = PhpOps.toStr(tzVal);
            TimelibTime t;
            if (tzType == TIMELIB_ZONETYPE_ID) {
                DateTimeZoneState tzState = new DateTimeZoneState();
                String[] errMsg = new String[1];
                if (!timezoneInitialize(tzState, tz, errMsg)) {
                    throw throwDateException(ctx, "DateException", "Invalid serialization data for " + className + " object");
                }
                PhpClass tzCls = ctx.lookupClass("DateTimeZone");
                PhpObject tzObj = new PhpObject(tzCls, ctx.nextObjectId());
                tzObj.setNativeState(tzState);
                t = dateInitialize(ctx, dstr, null, tzObj, false, false);
            } else {
                t = dateInitialize(ctx, dstr + " " + tz, null, null, false, false);
            }
            if (t == null) {
                throw throwDateException(ctx, "DateException", "Invalid serialization data for " + className + " object");
            }
            self.setNativeState(new DateTimeState(t));
            applyDynamicProperties(self, data, java.util.Set.of("date", "timezone_type", "timezone"));
            return PhpNull.INSTANCE;
        });

        addMethod(language, methods, "__wakeup", false, (ctx, self, args) -> PhpNull.INSTANCE);

        addMethod(language, methods, "__set_state", true, (ctx, self, args) -> {
            if (args.length != 1 || !(args[0] instanceof PhpArray data)) {
                throw new PhpError(PhpError.TYPE_ERROR,
                        className + "::__set_state(): Argument #1 ($array) must be of type array", null);
            }
            PhpClass cls = ctx.lookupClass(className);
            PhpObject obj = new PhpObject(cls, ctx.nextObjectId());
            Object dateVal = data.get("date");
            Object tzTypeVal = data.get("timezone_type");
            Object tzVal = data.get("timezone");
            if (dateVal == null || tzTypeVal == null || tzVal == null) {
                throw throwDateException(ctx, "DateException", "Invalid serialization data for " + className + " object");
            }
            String dstr = PhpOps.toStr(dateVal);
            long tzType = PhpOps.toLong(tzTypeVal);
            String tz = PhpOps.toStr(tzVal);
            TimelibTime t;
            if (tzType == TIMELIB_ZONETYPE_ID) {
                DateTimeZoneState tzState = new DateTimeZoneState();
                String[] errMsg = new String[1];
                if (!timezoneInitialize(tzState, tz, errMsg)) {
                    throw throwDateException(ctx, "DateException", "Invalid serialization data for " + className + " object");
                }
                PhpClass tzCls = ctx.lookupClass("DateTimeZone");
                PhpObject tzObj = new PhpObject(tzCls, ctx.nextObjectId());
                tzObj.setNativeState(tzState);
                t = dateInitialize(ctx, dstr, null, tzObj, false, false);
            } else {
                t = dateInitialize(ctx, dstr + " " + tz, null, null, false, false);
            }
            if (t == null) {
                throw throwDateException(ctx, "DateException", "Invalid serialization data for " + className + " object");
            }
            obj.setNativeState(new DateTimeState(t));
            return obj;
        });

        return new PhpClassTemplate(
                language.nextTemplateId(), className, false, false, false, false,
                null, List.of("datetimeinterface"), List.of(), List.of(), methods);
    }

    /** Native state for {@code DatePeriod} objects. */
    public static final class DatePeriodState implements PhpNativeState {
        public PhpObject start;
        public PhpObject end;
        public PhpObject interval;
        public Long recurrences;
        public boolean includeStartDate = true;
        public boolean includeEndDate = false;
        public boolean initialized;

        @Override
        public PhpNativeState copy() {
            DatePeriodState c = new DatePeriodState();
            c.start = this.start;
            c.end = this.end;
            c.interval = this.interval;
            c.recurrences = this.recurrences;
            c.includeStartDate = this.includeStartDate;
            c.includeEndDate = this.includeEndDate;
            c.initialized = this.initialized;
            return c;
        }

        @Override
        public Map<String, Object> debugProperties(PhpObject self) {
            if (!initialized) {
                return null;
            }
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("start", start != null ? start : PhpNull.INSTANCE);
            props.put("current", start != null ? start : PhpNull.INSTANCE);
            props.put("end", end != null ? end : PhpNull.INSTANCE);
            props.put("interval", interval != null ? interval : PhpNull.INSTANCE);
            props.put("recurrences", recurrences != null ? recurrences : PhpNull.INSTANCE);
            props.put("include_start_date", includeStartDate);
            props.put("include_end_date", includeEndDate);
            return props;
        }
    }

    private static PhpClassTemplate buildDatePeriodClass(PhpLanguage language) {
        List<PhpClassTemplate.ConstantDecl> constants = List.of(
                constDecl("EXCLUDE_START_DATE", 1L),
                constDecl("INCLUDE_END_DATE", 4L)
        );

        List<PhpClassTemplate.PropertyDecl> props = List.of(
                new PhpClassTemplate.PropertyDecl("start", Visibility.PUBLIC, false, null, null),
                new PhpClassTemplate.PropertyDecl("current", Visibility.PUBLIC, false, null, null),
                new PhpClassTemplate.PropertyDecl("end", Visibility.PUBLIC, false, null, null),
                new PhpClassTemplate.PropertyDecl("interval", Visibility.PUBLIC, false, null, null),
                new PhpClassTemplate.PropertyDecl("recurrences", Visibility.PUBLIC, false, 1L, null),
                new PhpClassTemplate.PropertyDecl("include_start_date", Visibility.PUBLIC, false, true, null),
                new PhpClassTemplate.PropertyDecl("include_end_date", Visibility.PUBLIC, false, false, null)
        );

        Map<String, PhpClassTemplate.MethodDecl> methods = new LinkedHashMap<>();

        addMethod(language, methods, "__construct", false, (ctx, self, args) -> {
            if (args.length < 1) {
                throw new PhpError(PhpError.ARGUMENT_COUNT,
                        "DatePeriod::__construct() expects at least 1 argument, " + args.length + " given", null);
            }
            DatePeriodState state = new DatePeriodState();
            if (args[0] instanceof String || (args.length == 1 && !(args[0] instanceof PhpObject))) {
                String iso = PhpOps.toStr(args[0]);
                long options = args.length >= 2 ? PhpOps.toLong(args[1]) : 0L;
                TimelibErrorContainer[] err = new TimelibErrorContainer[1];
                ParseIsoIntervals.IsoIntervalResult res = ParseIsoIntervals.strToInterval(iso, err);
                if (err[0] != null && err[0].errorCount > 0) {
                    throw throwDateException(ctx, "DateMalformedPeriodStringException",
                            "Unknown or bad format (" + iso + ")");
                }
                if (res.period != null) {
                    PhpClass intCls = ctx.lookupClass("DateInterval");
                    PhpObject intObj = new PhpObject(intCls, ctx.nextObjectId());
                    intCls.populateDefaults(intObj);
                    DateIntervalState intState = new DateIntervalState(res.period);
                    intState.initialized = true;
                    intObj.setNativeState(intState);
                    syncPropertiesFromRelTime(intObj, res.period, false);
                    state.interval = intObj;
                }
                if (res.begin != null) {
                    PhpClass dtCls = ctx.lookupClass("DateTime");
                    PhpObject dtObj = new PhpObject(dtCls, ctx.nextObjectId());
                    dtObj.setNativeState(new DateTimeState(res.begin));
                    state.start = dtObj;
                }
                if (res.end != null) {
                    PhpClass dtCls = ctx.lookupClass("DateTime");
                    PhpObject dtObj = new PhpObject(dtCls, ctx.nextObjectId());
                    dtObj.setNativeState(new DateTimeState(res.end));
                    state.end = dtObj;
                }
                state.includeStartDate = (options & 1L) == 0;
                state.includeEndDate = (options & 4L) != 0;
                if (res.haveRecurrences) {
                    state.recurrences = (long) res.recurrences + (state.includeStartDate ? 1L : 0L) + (state.includeEndDate ? 1L : 0L);
                } else {
                    state.recurrences = 1L;
                }
                state.initialized = true;
                self.setNativeState(state);
                syncDatePeriodProperties(self, state);
                return PhpNull.INSTANCE;
            }

            if (!(args[0] instanceof PhpObject startObj) || !(startObj.nativeState() instanceof DateTimeState)) {
                throw new PhpError(PhpError.TYPE_ERROR,
                        "DatePeriod::__construct(): Argument #1 ($start) must be of type DateTimeInterface or string", null);
            }
            if (args.length < 2 || !(args[1] instanceof PhpObject intObj) || !(intObj.nativeState() instanceof DateIntervalState)) {
                throw new PhpError(PhpError.TYPE_ERROR,
                        "DatePeriod::__construct(): Argument #2 ($interval) must be of type DateInterval", null);
            }
            long options = args.length >= 4 ? PhpOps.toLong(args[3]) : 0L;
            state.includeStartDate = (options & 1L) == 0;
            state.includeEndDate = (options & 4L) != 0;
            state.start = startObj;
            state.interval = intObj;
            if (args.length >= 3) {
                if (args[2] instanceof PhpObject endObj && endObj.nativeState() instanceof DateTimeState) {
                    state.end = endObj;
                    state.recurrences = 1L;
                } else {
                    long nr = PhpOps.toLong(args[2]);
                    state.recurrences = nr + (state.includeStartDate ? 1L : 0L) + (state.includeEndDate ? 1L : 0L);
                }
            } else {
                state.recurrences = 1L;
            }
            state.initialized = true;
            self.setNativeState(state);
            syncDatePeriodProperties(self, state);
            return PhpNull.INSTANCE;
        });

        addMethod(language, methods, "getStartDate", false, (ctx, self, args) -> {
            DatePeriodState state = (DatePeriodState) self.nativeState();
            return state != null && state.start != null ? state.start : PhpNull.INSTANCE;
        });

        addMethod(language, methods, "getEndDate", false, (ctx, self, args) -> {
            DatePeriodState state = (DatePeriodState) self.nativeState();
            return state != null && state.end != null ? state.end : PhpNull.INSTANCE;
        });

        addMethod(language, methods, "getDateInterval", false, (ctx, self, args) -> {
            DatePeriodState state = (DatePeriodState) self.nativeState();
            return state != null && state.interval != null ? state.interval : PhpNull.INSTANCE;
        });

        addMethod(language, methods, "getRecurrences", false, (ctx, self, args) -> {
            DatePeriodState state = (DatePeriodState) self.nativeState();
            if (state == null || state.recurrences == null) {
                return PhpNull.INSTANCE;
            }
            long calc = state.recurrences - (state.includeStartDate ? 1L : 0L) - (state.includeEndDate ? 1L : 0L);
            return calc == 0 ? PhpNull.INSTANCE : calc;
        });

        addMethod(language, methods, "__serialize", false, (ctx, self, args) -> {
            DatePeriodState state = (DatePeriodState) self.nativeState();
            PhpArray out = new PhpArray();
            if (state != null) {
                out.put("start", state.start != null ? state.start : PhpNull.INSTANCE);
                out.put("current", state.start != null ? state.start : PhpNull.INSTANCE);
                out.put("end", state.end != null ? state.end : PhpNull.INSTANCE);
                out.put("interval", state.interval != null ? state.interval : PhpNull.INSTANCE);
                out.put("recurrences", state.recurrences != null ? state.recurrences : 1L);
                out.put("include_start_date", state.includeStartDate);
                out.put("include_end_date", state.includeEndDate);
            }
            return out;
        });

        addMethod(language, methods, "__unserialize", false, (ctx, self, args) -> PhpNull.INSTANCE);
        addMethod(language, methods, "__wakeup", false, (ctx, self, args) -> PhpNull.INSTANCE);

        return new PhpClassTemplate(
                language.nextTemplateId(), "DatePeriod", false, false, false, false,
                null, List.of(), props, constants, methods);
    }

    private static void syncDatePeriodProperties(PhpObject self, DatePeriodState state) {
        DynamicObject.PutNode put = DynamicObject.PutNode.getUncached();
        put.execute(self, "start", state.start != null ? state.start : PhpNull.INSTANCE);
        put.execute(self, "current", PhpNull.INSTANCE);
        put.execute(self, "end", state.end != null ? state.end : PhpNull.INSTANCE);
        put.execute(self, "interval", state.interval != null ? state.interval : PhpNull.INSTANCE);
        put.execute(self, "recurrences", state.recurrences != null ? state.recurrences : 1L);
        put.execute(self, "include_start_date", state.includeStartDate);
        put.execute(self, "include_end_date", state.includeEndDate);
    }

    /** php's DateTime/DateTimeImmutable/DateTimeZone/DateInterval __serialize include dynamic properties. */
    private static final java.util.Set<String> DATEINTERVAL_PROPERTIES = java.util.Set.of(
            "y", "m", "d", "h", "i", "s", "f", "invert", "days", "from_string");

    private static void appendDynamicProperties(PhpObject self, PhpArray out) {
        appendDynamicProperties(self, out, java.util.Set.of());
    }

    private static void appendDynamicProperties(PhpObject self, PhpArray out, java.util.Set<String> declared) {
        DynamicObject.GetNode get = DynamicObject.GetNode.getUncached();
        for (Object key : DynamicObject.GetKeyArrayNode.getUncached().execute(self)) {
            String name = (String) key;
            if (!out.containsKey(name) && !declared.contains(name)) {
                out.put(name, get.execute(self, name, PhpNull.INSTANCE));
            }
        }
    }

    /** Restores properties from __unserialize() data that the date code above did not consume. */
    private static void applyDynamicProperties(PhpObject self, PhpArray data, java.util.Set<String> handled) {
        DynamicObject.PutNode put = DynamicObject.PutNode.getUncached();
        for (var entry : data.entries()) {
            String name = PhpOps.toStr(entry.getKey());
            if (!handled.contains(name)) {
                put.execute(self, name, entry.getValue());
            }
        }
    }

    private static PhpException throwDateException(PhpContext ctx, String className, String message) {
        Node site = ctx.currentBuiltinCallSite();
        String file = site != null && site.getEncapsulatingSourceSection() != null
                ? site.getEncapsulatingSourceSection().getSource().getName() : "";
        int line = site != null && site.getEncapsulatingSourceSection() != null
                ? site.getEncapsulatingSourceSection().getStartLine() : 0;
        PhpBacktrace.LazyTrace trace = PhpBacktrace.captureFromBuiltin(site, null);
        PhpObject obj = PhpExceptions.createBuiltinThrowable(ctx, className, message, 0L, file, line, trace);
        return new PhpException(obj, site);
    }

    private static PhpException throwDateError(PhpContext ctx, String className, String message) {
        return throwDateException(ctx, className, message);
    }
}
