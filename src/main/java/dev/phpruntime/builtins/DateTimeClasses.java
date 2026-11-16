// Glue code translated from php-src ext/date/php_date.c (PHP License 3.01).

package dev.phpruntime.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
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
import dev.phpruntime.timelib.ParseDate;
import dev.phpruntime.timelib.ParseTz;
import dev.phpruntime.timelib.TimelibTime;
import dev.phpruntime.timelib.TimelibTzinfo;
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
        public final TimelibTime time;

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
            PhpArray out = new PhpArray();
            TimelibTzinfo tz = state.tzInfo;
            if (tz.trans != null) {
                for (int i = 0; i < tz.bit64.timecnt; i++) {
                    long ts = tz.trans[i];
                    if (ts >= begin && ts <= end) {
                        TimelibTzinfo.Ttinfo tt = tz.type[tz.transIdx[i]];
                        PhpArray entry = new PhpArray();
                        entry.put("ts", ts);
                        entry.put("isdst", tt.isdst != 0);
                        entry.put("offset", (long) tt.offset);
                        entry.put("abbr", tz.getAbbr(tt.abbrIdx));
                        out.append(entry);
                    }
                }
            }
            return out;
        });

        addMethod(language, methods, "listAbbreviations", true, (ctx, self, args) -> new PhpArray());

        addMethod(language, methods, "__serialize", false, (ctx, self, args) -> {
            DateTimeZoneState state = requireInitializedZone(self);
            PhpArray out = new PhpArray();
            out.put("timezone_type", (long) state.type);
            out.put("timezone", state.name());
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
