// Ported from timelib 2022.17 (ext/date/lib/parse_date.re),
// Copyright (c) 2015-2023 Derick Rethans, Copyright (c) 2018 MongoDB, Inc.,
// MIT License (see src/main/resources/META-INF/LICENSE-timelib).

package dev.phpruntime.timelib;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dev.phpruntime.timelib.TimelibConstants.*;

/**
 * Port of {@code parse_date.re} ({@code timelib_strtotime} and scanner).
 *
 * <h2>Macro equivalents and visible control flow</h2>
 * Several C macros in {@code parse_date.re} contain hidden control flow (adding an error and
 * returning {@code TIMELIB_ERROR} from {@code scan()}). In Java these return {@code boolean} so
 * the early return is explicit at every call site:
 * <ul>
 *   <li>{@code TIMELIB_INIT} &rarr; {@code String str = timelibInit(s, cursor); Cursor ptr = new Cursor(str);}</li>
 *   <li>{@code TIMELIB_DEINIT} &rarr; no-op (GC handles {@code str})</li>
 *   <li>{@code TIMELIB_HAVE_TIME()} &rarr; {@code if (!timelibHaveTime(s)) return TIMELIB_ERROR;}</li>
 *   <li>{@code TIMELIB_UNHAVE_TIME()} &rarr; {@code timelibUnhaveTime(s);}</li>
 *   <li>{@code TIMELIB_HAVE_DATE()} &rarr; {@code if (!timelibHaveDate(s)) return TIMELIB_ERROR;}</li>
 *   <li>{@code TIMELIB_UNHAVE_DATE()} &rarr; {@code timelibUnhaveDate(s);}</li>
 *   <li>{@code TIMELIB_HAVE_RELATIVE()} &rarr; {@code timelibHaveRelative(s);}</li>
 *   <li>{@code TIMELIB_HAVE_WEEKDAY_RELATIVE()} &rarr; {@code timelibHaveWeekdayRelative(s);}</li>
 *   <li>{@code TIMELIB_HAVE_SPECIAL_RELATIVE()} &rarr; {@code timelibHaveSpecialRelative(s);}</li>
 *   <li>{@code TIMELIB_HAVE_TZ()} &rarr; {@code if (!timelibHaveTz(s, cursor)) return TIMELIB_ERROR;}</li>
 *   <li>{@code TIMELIB_PROCESS_YEAR(x, l)} &rarr; {@code x = timelibProcessYear(x, l);}</li>
 * </ul>
 *
 * <h2>re2c scanner semantics</h2>
 * At each cursor position, every rule is tested with {@code Matcher.region(cursor, s.lim).lookingAt()};
 * the longest match wins, with ties broken in favor of the rule listed first. Rules return a
 * token code (e.g. {@link #TIMELIB_RELATIVE}) or {@link #LOOP_STD} to continue the {@code std:} loop.
 */
public final class ParseDate {

    private ParseDate() {
    }

    public static final int EOI = 257;
    public static final int TIME = 258;
    public static final int DATE = 259;

    public static final int TIMELIB_XMLRPC_SOAP = 260;
    public static final int TIMELIB_TIME12 = 261;
    public static final int TIMELIB_TIME24 = 262;
    public static final int TIMELIB_GNU_NOCOLON = 263;
    public static final int TIMELIB_GNU_NOCOLON_TZ = 264;
    public static final int TIMELIB_ISO_NOCOLON = 265;

    public static final int TIMELIB_AMERICAN = 266;
    public static final int TIMELIB_ISO_DATE = 267;
    public static final int TIMELIB_DATE_FULL = 268;
    public static final int TIMELIB_DATE_TEXT = 269;
    public static final int TIMELIB_DATE_NOCOLON = 270;
    public static final int TIMELIB_PG_YEARDAY = 271;
    public static final int TIMELIB_PG_TEXT = 272;
    public static final int TIMELIB_PG_REVERSE = 273;
    public static final int TIMELIB_CLF = 274;
    public static final int TIMELIB_DATE_NO_DAY = 275;
    public static final int TIMELIB_SHORTDATE_WITH_TIME = 276;
    public static final int TIMELIB_DATE_FULL_POINTED = 277;
    public static final int TIMELIB_TIME24_WITH_ZONE = 278;
    public static final int TIMELIB_ISO_WEEK = 279;
    public static final int TIMELIB_LF_DAY_OF_MONTH = 280;
    public static final int TIMELIB_WEEK_DAY_OF_MONTH = 281;

    public static final int TIMELIB_TIMEZONE = 300;
    public static final int TIMELIB_AGO = 301;

    public static final int TIMELIB_RELATIVE = 310;

    public static final int TIMELIB_ERROR = 999;
    public static final int TIMELIB_MAX_ERRORS = 1000;

    /** Sentinel returned by rule actions that {@code goto std;} in C. */
    private static final int LOOP_STD = -1;

    // ------------------------------------------------------------------ named regex definitions (96 from parse_date.re)

    private static String g(String regex) {
        return "(?:" + regex + ")";
    }

    private static String ci(String literal) {
        return "(?i:" + Pattern.quote(literal) + ")";
    }

    private static String cs(String literal) {
        return Pattern.quote(literal);
    }

    private static final String ANY = g("[\\u0000-\\uffff]");

    private static final String NBSP = g("\\u00c2\\u00a0|\\u00a0");
    private static final String NNBSP = g("\\u00e2\\u0080\\u00af|\\u202f");
    private static final String SPACE = g("[ \\t]+|" + NBSP + "+|" + NNBSP + "+");
    private static final String FRAC = g("\\.[0-9]+");

    private static final String AGO = ci("ago");

    private static final String HOUR24 = g("[01]?[0-9]|2[0-4]");
    private static final String HOUR24LZ = g("[01][0-9]|2[0-4]");
    private static final String HOUR12 = g("0?[1-9]|1[0-2]");
    private static final String MINUTE = g("[0-5]?[0-9]");
    private static final String MINUTELZ = g("[0-5][0-9]");
    private static final String SECOND = g(MINUTE + "|60");
    private static final String SECONDLZ = g(MINUTELZ + "|60");
    private static final String MERIDIAN = g("([AaPp]\\.?[Mm]\\.?)[\u0000\\t ]");
    private static final String TZ = g("\\(?[A-Za-z]{1,6}\\)?|[A-Z][a-z]+(?:[_/-][A-Za-z]+)+");
    private static final String TZCORRECTION = g("(?:GMT)?[+-](?:(?:" + HOUR24 + "(?::?" + MINUTE + ")?)|(?:" + HOUR24LZ + MINUTELZ + SECONDLZ + ")|(?:" + HOUR24LZ + ":" + MINUTELZ + ":" + SECONDLZ + "))");

    private static final String DAYSUF = g("st|nd|rd|th");

    private static final String MONTH = g("0?[0-9]|1[0-2]");
    private static final String DAY = g("(?:(?:[0-2]?[0-9])|(?:3[01]))" + DAYSUF + "?");
    private static final String YEAR = g("[0-9]{1,4}");
    private static final String YEAR2 = g("[0-9]{2}");
    private static final String YEAR4 = g("[0-9]{4}");
    private static final String YEAR4WITHSIGN = g("[+-]?[0-9]{4}");
    private static final String YEARX = g("[+-][0-9]{5,19}");

    private static final String DAYOFYEAR = g("00[1-9]|0[1-9][0-9]|[1-2][0-9][0-9]|3[0-5][0-9]|36[0-6]");
    private static final String WEEKOFYEAR = g("0[1-9]|[1-4][0-9]|5[0-3]");

    private static final String MONTHLZ = g("0[0-9]|1[0-2]");
    private static final String DAYLZ = g("0[0-9]|[1-2][0-9]|3[01]");

    private static final String DAYFULLS = g(ci("sundays") + "|" + ci("mondays") + "|" + ci("tuesdays") + "|" + ci("wednesdays") + "|" + ci("thursdays") + "|" + ci("fridays") + "|" + ci("saturdays"));
    private static final String DAYFULL = g(ci("sunday") + "|" + ci("monday") + "|" + ci("tuesday") + "|" + ci("wednesday") + "|" + ci("thursday") + "|" + ci("friday") + "|" + ci("saturday"));
    private static final String DAYABBR = g(ci("sun") + "|" + ci("mon") + "|" + ci("tue") + "|" + ci("wed") + "|" + ci("thu") + "|" + ci("fri") + "|" + ci("sat") + "|" + ci("sun"));
    private static final String DAYSPECIAL = g(ci("weekday") + "|" + ci("weekdays"));
    private static final String DAYTEXT = g(DAYFULLS + "|" + DAYFULL + "|" + DAYABBR + "|" + DAYSPECIAL);

    private static final String MONTHFULL = g(ci("january") + "|" + ci("february") + "|" + ci("march") + "|" + ci("april") + "|" + ci("may") + "|" + ci("june") + "|" + ci("july") + "|" + ci("august") + "|" + ci("september") + "|" + ci("october") + "|" + ci("november") + "|" + ci("december"));
    private static final String MONTHABBR = g(ci("jan") + "|" + ci("feb") + "|" + ci("mar") + "|" + ci("apr") + "|" + ci("may") + "|" + ci("jun") + "|" + ci("jul") + "|" + ci("aug") + "|" + ci("sep") + "|" + ci("sept") + "|" + ci("oct") + "|" + ci("nov") + "|" + ci("dec"));
    private static final String MONTHROMAN = g("I|II|III|IV|V|VI|VII|VIII|IX|X|XI|XII");
    private static final String MONTHTEXT = g(MONTHFULL + "|" + MONTHABBR + "|" + MONTHROMAN);

    // Time formats
    private static final String TIMETINY12 = g(HOUR12 + SPACE + "?" + MERIDIAN);
    private static final String TIMESHORT12 = g(HOUR12 + "[:.]" + MINUTELZ + SPACE + "?" + MERIDIAN);
    private static final String TIMELONG12 = g(HOUR12 + "[:.]" + MINUTE + "[:.]" + SECONDLZ + SPACE + "?" + MERIDIAN);

    private static final String TIMETINY24 = g(ci("t") + HOUR24);
    private static final String TIMESHORT24 = g(ci("t") + "?" + HOUR24 + "[:.]" + MINUTE);
    private static final String TIMELONG24 = g(ci("t") + "?" + HOUR24 + "[:.]" + MINUTE + "[:.]" + SECOND);
    private static final String ISO8601LONG = g(ci("t") + "?" + HOUR24 + "[:.]" + MINUTE + "[:.]" + SECOND + FRAC);

    private static final String ISO8601NORMTZ = g(ci("t") + "?" + HOUR24 + "[:.]" + MINUTE + "[:.]" + SECONDLZ + SPACE + "?" + g(TZCORRECTION + "|" + TZ));

    private static final String GNUNOCOLON = g(ci("t") + "?" + HOUR24LZ + MINUTELZ);
    private static final String ISO8601NOCOLON = g(ci("t") + "?" + HOUR24LZ + MINUTELZ + SECONDLZ);

    // Date formats
    private static final String AMERICANSHORT = g(MONTH + "/" + DAY);
    private static final String AMERICAN = g(MONTH + "/" + DAY + "/" + YEAR);
    private static final String ISO8601DATESLASH = g(YEAR4 + "/" + MONTHLZ + "/" + DAYLZ + "/?");
    private static final String DATESLASH = g(YEAR4 + "/" + MONTH + "/" + DAY);
    private static final String ISO8601DATE4 = g(YEAR4WITHSIGN + "-" + MONTHLZ + "-" + DAYLZ);
    private static final String ISO8601DATE2 = g(YEAR2 + "-" + MONTHLZ + "-" + DAYLZ);
    private static final String ISO8601DATEX = g(YEARX + "-" + MONTHLZ + "-" + DAYLZ);
    private static final String GNUDATESHORTER = g(YEAR4 + "-" + MONTH);
    private static final String GNUDATESHORT = g(YEAR + "-" + MONTH + "-" + DAY);
    private static final String POINTEDDATE4 = g(DAY + "[.\\t-]" + MONTH + "[.-]" + YEAR4);
    private static final String POINTEDDATE2 = g(DAY + "[.\\t]" + MONTH + "\\." + YEAR2);
    private static final String DATEFULL = g(DAY + "(?:[ \\t.-])*" + MONTHTEXT + "(?:[ \\t.-])*" + YEAR);
    private static final String DATENODAY = g(MONTHTEXT + "(?:[ .\\t-])*" + YEAR4);
    private static final String DATENODAYREV = g(YEAR4 + "(?:[ .\\t-])*" + MONTHTEXT);
    private static final String DATETEXTUAL = g(MONTHTEXT + "(?:[ .\\t-])*" + DAY + "[,.stndrh\\t ]+" + YEAR);
    private static final String DATENOYEAR = g(MONTHTEXT + "(?:[ .\\t-])*" + DAY + "(?:[,.stndrh\\t ]+|[\u0000])");
    private static final String DATENOYEARREV = g(DAY + "(?:[ .\\t-])*" + MONTHTEXT);
    private static final String DATENOCOLON = g(YEAR4 + MONTHLZ + DAYLZ);

    // Special formats
    private static final String SOAP = g(YEAR4 + "-" + MONTHLZ + "-" + DAYLZ + "T" + HOUR24LZ + ":" + MINUTELZ + ":" + SECONDLZ + FRAC + TZCORRECTION + "?");
    private static final String XMLRPC = g(YEAR4 + MONTHLZ + DAYLZ + "T" + HOUR24 + ":" + MINUTELZ + ":" + SECONDLZ);
    private static final String XMLRPCNOCOLON = g(YEAR4 + MONTHLZ + DAYLZ + ci("t") + HOUR24 + MINUTELZ + SECONDLZ);
    private static final String WDDX = g(YEAR4 + "-" + MONTH + "-" + DAY + "T" + HOUR24 + ":" + MINUTE + ":" + SECOND);
    private static final String PGYDOTD = g(YEAR4 + "[.-]?" + DAYOFYEAR);
    private static final String PGTEXTSHORT = g(MONTHABBR + "-" + DAYLZ + "-" + YEAR);
    private static final String PGTEXTREVERSE = g(YEAR + "-" + MONTHABBR + "-" + DAYLZ);
    private static final String MSSQLTIME = g(HOUR12 + ":" + MINUTELZ + ":" + SECONDLZ + "[:.][0-9]+" + MERIDIAN);
    private static final String ISOWEEKDAY = g(YEAR4 + "-?W" + WEEKOFYEAR + "-?[0-7]");
    private static final String ISOWEEK = g(YEAR4 + "-?W" + WEEKOFYEAR);
    private static final String EXIF = g(YEAR4 + ":" + MONTHLZ + ":" + DAYLZ + " " + HOUR24LZ + ":" + MINUTELZ + ":" + SECONDLZ);
    private static final String FIRSTDAYOF = ci("first day of");
    private static final String LASTDAYOF = ci("last day of");
    private static final String BACKOF = g(ci("back of ") + HOUR24 + "(?:" + SPACE + "?" + MERIDIAN + ")?");
    private static final String FRONTOF = g(ci("front of ") + HOUR24 + "(?:" + SPACE + "?" + MERIDIAN + ")?");

    // Common Log Format
    private static final String CLF = g(DAY + "/" + MONTHABBR + "/" + YEAR4 + ":" + HOUR24LZ + ":" + MINUTELZ + ":" + SECONDLZ + SPACE + TZCORRECTION);

    // Timestamp formats
    private static final String TIMESTAMP = g("@-?[0-9]+");
    private static final String TIMESTAMPMS = g("@-?[0-9]+\\.[0-9]{0,6}");

    // Ambiguity fixes
    private static final String DATESHORTWITHTIMESHORT12 = g(DATENOYEAR + TIMESHORT12);
    private static final String DATESHORTWITHTIMELONG12 = g(DATENOYEAR + TIMELONG12);
    private static final String DATESHORTWITHTIMESHORT = g(DATENOYEAR + TIMESHORT24);
    private static final String DATESHORTWITHTIMELONG = g(DATENOYEAR + TIMELONG24);
    private static final String DATESHORTWITHTIMELONGTZ = g(DATENOYEAR + ISO8601NORMTZ);

    // Relative regexps
    private static final String RELTEXTNUMBER = g(ci("first") + "|" + ci("second") + "|" + ci("third") + "|" + ci("fourth") + "|" + ci("fifth") + "|" + ci("sixth") + "|" + ci("seventh") + "|" + ci("eight") + "|" + ci("eighth") + "|" + ci("ninth") + "|" + ci("tenth") + "|" + ci("eleventh") + "|" + ci("twelfth"));
    private static final String RELTEXTTEXT = g(ci("next") + "|" + ci("last") + "|" + ci("previous") + "|" + ci("this"));
    private static final String RELTEXTUNIT = g(ci("ms") + "|" + ci("µs") + "|(?:" + g(ci("msec") + "|" + ci("millisecond") + "|" + ci("µsec") + "|" + ci("microsecond") + "|" + ci("usec") + "|" + ci("sec") + "|" + ci("second") + "|" + ci("min") + "|" + ci("minute") + "|" + ci("hour") + "|" + ci("day") + "|" + ci("fortnight") + "|" + ci("forthnight") + "|" + ci("month") + "|" + ci("year")) + "[sS]?)|" + ci("weeks") + "|" + DAYTEXT);

    private static final String RELNUMBER = g("[+-]*[ \\t]*[0-9]{1,13}");
    private static final String RELATIVE = g(RELNUMBER + SPACE + "?" + g(RELTEXTUNIT + "|" + ci("week")));
    private static final String RELATIVETEXT = g(g(RELTEXTNUMBER + "|" + RELTEXTTEXT) + SPACE + RELTEXTUNIT);
    private static final String RELATIVETEXTWEEK = g(RELTEXTTEXT + SPACE + ci("week"));

    private static final String WEEKDAYOF = g(g(RELTEXTNUMBER + "|" + RELTEXTTEXT) + SPACE + g(DAYFULLS + "|" + DAYFULL + "|" + DAYABBR) + SPACE + ci("of"));

    // ------------------------------------------------------------------ scanner state & cursor

    public static final class Cursor {
        public final String s;
        public int pos;

        public Cursor(String s) {
            this.s = s;
        }

        public char ch() {
            return pos < s.length() ? s.charAt(pos) : '\0';
        }

        public char ch(int offset) {
            int p = pos + offset;
            return (p >= 0 && p < s.length()) ? s.charAt(p) : '\0';
        }

        public int remaining() {
            return s.length() - pos;
        }
    }

    public static final class Scanner {
        public String str;
        public int lim;
        public int cur;
        public int tok;
        public int pos;
        public int line;
        public int len;
        public TimelibErrorContainer errors;
        public TimelibTime time;
        public ZoneinfoDb tzdb;
        Matcher[] matchers;
    }

    @FunctionalInterface
    private interface RuleAction {
        int execute(Scanner s, int cursor);
    }

    private static final class Rule {
        final String name;
        final Pattern pattern;
        final RuleAction action;

        Rule(String name, String regex, RuleAction action) {
            this.name = name;
            this.pattern = Pattern.compile(regex);
            this.action = action;
        }
    }

    private static final Rule[] RULES = buildRules();

    private static Rule[] buildRules() {
        List<Rule> r = new ArrayList<>();

        // TODO (Phase C): 'yesterday'
        // TODO (Phase C): 'now'
        // TODO (Phase C): 'noon'
        // TODO (Phase C): 'midnight' | 'today'
        // TODO (Phase C): 'tomorrow'

        // Example rule 1: timestamp
        r.add(new Rule("timestamp", TIMESTAMP, (s, cursor) -> {
            String str = timelibInit(s, cursor);
            Cursor ptr = new Cursor(str);
            timelibHaveRelative(s);
            timelibUnhaveDate(s);
            timelibUnhaveTime(s);
            if (!timelibHaveTz(s, cursor)) {
                return TIMELIB_ERROR;
            }

            long i = timelibGetSignedNr(s, ptr, 24);
            s.time.y = 1970;
            s.time.m = 1;
            s.time.d = 1;
            s.time.h = 0;
            s.time.i = 0;
            s.time.s = 0;
            s.time.us = 0;
            s.time.relative.s += i;
            s.time.isLocaltime = 1;
            s.time.zoneType = TIMELIB_ZONETYPE_OFFSET;
            s.time.z = 0;
            s.time.dst = 0;

            return TIMELIB_RELATIVE;
        }));

        // TODO (Phase C): timestampms
        // TODO (Phase C): firstdayof | lastdayof
        // TODO (Phase C): backof | frontof
        // TODO (Phase C): weekdayof
        // TODO (Phase C): 'this week' (relativetextweek)
        // TODO (Phase C): 'last week' / 'previous week' / 'next week'
        // TODO (Phase C): timetiny12 | timeshort12 | timelong12
        // TODO (Phase C): timetiny24
        // TODO (Phase C): timeshort24 | timelong24 | iso8601long
        // TODO (Phase C): iso8601normtz

        // Example rule 2: gnunocolon
        r.add(new Rule("gnunocolon", GNUNOCOLON, (s, cursor) -> {
            String str = timelibInit(s, cursor);
            Cursor ptr = new Cursor(str);
            switch (s.time.haveTime) {
                case 0:
                    s.time.h = timelibGetNr(ptr, 2);
                    s.time.i = timelibGetNr(ptr, 2);
                    s.time.s = 0;
                    break;
                case 1:
                    s.time.y = timelibGetNr(ptr, 4);
                    break;
                default:
                    addError(s, TIMELIB_ERR_DOUBLE_TIME, "Double time specification");
                    return TIMELIB_ERROR;
            }
            s.time.haveTime++;
            return TIMELIB_GNU_NOCOLON;
        }));

        // TODO (Phase C): iso8601nocolon
        // TODO (Phase C): americanshort | american

        // Example rule 3: iso8601date4 | iso8601dateslash | dateslash
        r.add(new Rule("iso8601date4 | iso8601dateslash | dateslash",
                ISO8601DATE4 + "|" + ISO8601DATESLASH + "|" + DATESLASH, (s, cursor) -> {
            String str = timelibInit(s, cursor);
            Cursor ptr = new Cursor(str);
            if (!timelibHaveDate(s)) {
                return TIMELIB_ERROR;
            }
            s.time.y = timelibGetSignedNr(s, ptr, 4);
            s.time.m = timelibGetNr(ptr, 2);
            s.time.d = timelibGetNr(ptr, 2);
            return TIMELIB_ISO_DATE;
        }));

        // TODO (Phase C): iso8601date2
        // TODO (Phase C): iso8601datex
        // TODO (Phase C): gnudateshorter
        // TODO (Phase C): gnudateshort
        // TODO (Phase C): pointeddate4
        // TODO (Phase C): pointeddate2
        // TODO (Phase C): datefull
        // TODO (Phase C): datenoday
        // TODO (Phase C): datenodayrev
        // TODO (Phase C): datetextual | datenoyear
        // TODO (Phase C): datenoyearrev
        // TODO (Phase C): datenocolon
        // TODO (Phase C): soap
        // TODO (Phase C): xmlrpc
        // TODO (Phase C): xmlrpcnocolon
        // TODO (Phase C): wddx
        // TODO (Phase C): pgydotd
        // TODO (Phase C): pgtextshort
        // TODO (Phase C): pgtextreverse
        // TODO (Phase C): mssqltime
        // TODO (Phase C): isoweekday
        // TODO (Phase C): isoweek
        // TODO (Phase C): exif
        // TODO (Phase C): clf
        // TODO (Phase C): timestampms (wait: listed above after timestamp)
        // TODO (Phase C): relative
        // TODO (Phase C): relativetext
        // TODO (Phase C): ago
        // TODO (Phase C): daytext
        // TODO (Phase C): tzcorrection | tz
        // TODO (Phase C): dateshortwithtimeshort12 | dateshortwithtimelong12
        // TODO (Phase C): dateshortwithtimeshort | dateshortwithtimelong | dateshortwithtimelongtz

        r.add(new Rule("[.,]", "[.,]", (s, cursor) -> {
            s.cur = cursor;
            return LOOP_STD;
        }));

        r.add(new Rule("space", SPACE, (s, cursor) -> {
            s.cur = cursor;
            return LOOP_STD;
        }));

        r.add(new Rule("nul_or_nl", "\\u0000|\\n", (s, cursor) -> {
            s.cur = cursor;
            s.pos = cursor;
            s.line++;
            return LOOP_STD;
        }));

        r.add(new Rule("any", ANY, (s, cursor) -> {
            s.cur = cursor;
            addError(s, TIMELIB_ERR_UNEXPECTED_CHARACTER, "Unexpected character");
            return LOOP_STD;
        }));

        return r.toArray(new Rule[0]);
    }

    // ------------------------------------------------------------------ macro equivalents

    private static String timelibInit(Scanner s, int cursor) {
        s.cur = cursor;
        return s.str.substring(s.tok, s.cur);
    }

    private static boolean timelibHaveTime(Scanner s) {
        if (s.time.haveTime != 0) {
            addError(s, TIMELIB_ERR_DOUBLE_TIME, "Double time specification");
            return false;
        }
        s.time.haveTime = 1;
        s.time.h = 0;
        s.time.i = 0;
        s.time.s = 0;
        s.time.us = 0;
        return true;
    }

    private static void timelibUnhaveTime(Scanner s) {
        s.time.haveTime = 0;
        s.time.h = 0;
        s.time.i = 0;
        s.time.s = 0;
        s.time.us = 0;
    }

    private static boolean timelibHaveDate(Scanner s) {
        if (s.time.haveDate != 0) {
            addError(s, TIMELIB_ERR_DOUBLE_DATE, "Double date specification");
            return false;
        }
        s.time.haveDate = 1;
        return true;
    }

    private static void timelibUnhaveDate(Scanner s) {
        s.time.haveDate = 0;
        s.time.d = 0;
        s.time.m = 0;
        s.time.y = 0;
    }

    private static void timelibHaveRelative(Scanner s) {
        s.time.haveRelative = 1;
    }

    private static void timelibHaveWeekdayRelative(Scanner s) {
        s.time.haveRelative = 1;
        s.time.relative.haveWeekdayRelative = 1;
    }

    private static void timelibHaveSpecialRelative(Scanner s) {
        s.time.haveRelative = 1;
        s.time.relative.haveSpecialRelative = 1;
    }

    private static boolean timelibHaveTz(Scanner s, int cursor) {
        s.cur = cursor;
        if (s.time.haveZone != 0) {
            if (s.time.haveZone > 1) {
                addError(s, TIMELIB_ERR_DOUBLE_TZ, "Double timezone specification");
            } else {
                addWarning(s, TIMELIB_WARN_DOUBLE_TZ, "Double timezone specification");
            }
            s.time.haveZone++;
            return false;
        }
        s.time.haveZone++;
        return true;
    }

    public static long timelibProcessYear(long x, int l) {
        if (x == TIMELIB_UNSET || l >= 4) {
            return x;
        } else if (x < 100) {
            if (x < 70) {
                return x + 2000;
            } else {
                return x + 1900;
            }
        }
        return x;
    }

    // ------------------------------------------------------------------ errors & helpers

    public static void addWarning(Scanner s, int errorCode, String error) {
        char ch = (s.str != null && s.tok < s.str.length()) ? s.str.charAt(s.tok) : '\0';
        s.errors.addWarning(errorCode, s.tok, ch, error);
    }

    public static void addError(Scanner s, int errorCode, String error) {
        char ch = (s.str != null && s.tok < s.str.length()) ? s.str.charAt(s.tok) : '\0';
        s.errors.addError(errorCode, s.tok, ch, error);
    }

    /** C: {@code timelib_get_nr_ex} */
    public static long timelibGetNrEx(Cursor ptr, int maxLength, int[] scannedLength) {
        while (ptr.ch() < '0' || ptr.ch() > '9') {
            if (ptr.ch() == '\0') {
                return TIMELIB_UNSET;
            }
            ptr.pos++;
        }
        int begin = ptr.pos;
        int len = 0;
        while (ptr.ch() >= '0' && ptr.ch() <= '9' && len < maxLength) {
            ptr.pos++;
            len++;
        }
        int end = ptr.pos;
        if (scannedLength != null) {
            scannedLength[0] = end - begin;
        }
        return Long.parseLong(ptr.s.substring(begin, end));
    }

    /** C: {@code timelib_get_nr} */
    public static long timelibGetNr(Cursor ptr, int maxLength) {
        return timelibGetNrEx(ptr, maxLength, null);
    }

    /** C: {@code timelib_get_signed_nr} */
    public static long timelibGetSignedNr(Scanner s, Cursor ptr, int maxLength) {
        while ((ptr.ch() < '0' || ptr.ch() > '9') && ptr.ch() != '+' && ptr.ch() != '-') {
            if (ptr.ch() == '\0') {
                addError(s, TIMELIB_ERR_UNEXPECTED_DATA, "Found unexpected data");
                return 0;
            }
            ptr.pos++;
        }

        StringBuilder sb = new StringBuilder();
        char sign = '+';
        while (ptr.ch() == '+' || ptr.ch() == '-') {
            if (ptr.ch() == '-') {
                sign = (sign == '+') ? '-' : '+';
            }
            ptr.pos++;
        }
        sb.append(sign);

        while (ptr.ch() < '0' || ptr.ch() > '9') {
            if (ptr.ch() == '\0') {
                addError(s, TIMELIB_ERR_UNEXPECTED_DATA, "Found unexpected data");
                return 0;
            }
            ptr.pos++;
        }

        int len = 0;
        while (ptr.ch() >= '0' && ptr.ch() <= '9' && len < maxLength) {
            sb.append(ptr.ch());
            ptr.pos++;
            len++;
        }

        try {
            return Long.parseLong(sb.toString());
        } catch (NumberFormatException e) {
            addError(s, TIMELIB_ERR_NUMBER_OUT_OF_RANGE, "Number out of range");
            return 0;
        }
    }

    private static int sHour(long a) {
        return (int) (a * 3600L);
    }

    private static int sMin(long a) {
        return (int) (a * 60L);
    }

    private static long strtolPrefix(String s, int start) {
        int i = start;
        while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
            i++;
        }
        return i == start ? 0 : Long.parseLong(s.substring(start, i));
    }

    /** C: {@code timelib_parse_tz_cor} */
    public static long timelibParseTzCor(Cursor ptr, int[] tzNotFound) {
        int begin = ptr.pos;
        tzNotFound[0] = 1;

        while ((ptr.ch() >= '0' && ptr.ch() <= '9') || ptr.ch() == ':') {
            ptr.pos++;
        }
        int end = ptr.pos;
        int len = end - begin;
        switch (len) {
            case 1:
            case 2:
                tzNotFound[0] = 0;
                return sHour(strtolPrefix(ptr.s, begin));
            case 3:
            case 4:
                if (ptr.s.charAt(begin + 1) == ':') {
                    tzNotFound[0] = 0;
                    return sHour(strtolPrefix(ptr.s, begin)) + sMin(strtolPrefix(ptr.s, begin + 2));
                } else if (ptr.s.charAt(begin + 2) == ':') {
                    tzNotFound[0] = 0;
                    return sHour(strtolPrefix(ptr.s, begin)) + sMin(strtolPrefix(ptr.s, begin + 3));
                } else {
                    tzNotFound[0] = 0;
                    long tmp = strtolPrefix(ptr.s, begin);
                    return sHour(tmp / 100) + sMin(tmp % 100);
                }
            case 5:
                if (ptr.s.charAt(begin + 2) != ':') {
                    break;
                }
                tzNotFound[0] = 0;
                return sHour(strtolPrefix(ptr.s, begin)) + sMin(strtolPrefix(ptr.s, begin + 3));
            case 6: {
                tzNotFound[0] = 0;
                long tmp = strtolPrefix(ptr.s, begin);
                return sHour(tmp / 10000) + sMin((tmp / 100) % 100) + (tmp % 100);
            }
            case 8:
                if (ptr.s.charAt(begin + 2) != ':' || ptr.s.charAt(begin + 5) != ':') {
                    break;
                }
                tzNotFound[0] = 0;
                return sHour(strtolPrefix(ptr.s, begin)) + sMin(strtolPrefix(ptr.s, begin + 3))
                        + strtolPrefix(ptr.s, begin + 6);
        }
        return 0;
    }

    /**
     * C: {@code timelib_lookup_abbr}
     *
     * <p>Phase A handles UTC/GMT directly; Phase C will port {@code timezonemap.h} and
     * {@code fallbackmap.h} for all abbreviations.
     */
    private static long timelibLookupAbbr(Cursor ptr, int[] dst, String[] tzAbbr, int[] found) {
        int begin = ptr.pos;
        while ((ptr.ch() >= 'A' && ptr.ch() <= 'Z')
                || (ptr.ch() >= 'a' && ptr.ch() <= 'z')
                || (ptr.ch() >= '0' && ptr.ch() <= '9')
                || ptr.ch() == '/' || ptr.ch() == '_' || ptr.ch() == '-' || ptr.ch() == '+') {
            ptr.pos++;
        }
        int end = ptr.pos;
        String word = ptr.s.substring(begin, end);
        long value = 0;
        if ("utc".equalsIgnoreCase(word) || "gmt".equalsIgnoreCase(word)) {
            dst[0] = 0;
            found[0] = 1;
        } else {
            found[0] = 0;
        }
        tzAbbr[0] = word;
        return value;
    }

    /** C: {@code timelib_parse_zone} */
    public static long timelibParseZone(Cursor ptr, int[] dst, TimelibTime t, int[] tzNotFound, ZoneinfoDb tzdb) {
        long retval = 0;
        int parenCount = 0;
        tzNotFound[0] = 0;

        while (ptr.ch() == ' ' || ptr.ch() == '\t' || ptr.ch() == '(') {
            if (ptr.ch() == '(') {
                parenCount++;
            }
            ptr.pos++;
        }
        if (ptr.ch(0) == 'G' && ptr.ch(1) == 'M' && ptr.ch(2) == 'T' && (ptr.ch(3) == '+' || ptr.ch(3) == '-')) {
            ptr.pos += 3;
        }
        if (ptr.ch() == '+') {
            ptr.pos++;
            t.isLocaltime = 1;
            t.zoneType = TIMELIB_ZONETYPE_OFFSET;
            t.dst = 0;
            retval = timelibParseTzCor(ptr, tzNotFound);
        } else if (ptr.ch() == '-') {
            ptr.pos++;
            t.isLocaltime = 1;
            t.zoneType = TIMELIB_ZONETYPE_OFFSET;
            t.dst = 0;
            retval = -1 * timelibParseTzCor(ptr, tzNotFound);
        } else {
            int[] found = new int[1];
            String[] tzAbbr = new String[1];
            t.isLocaltime = 1;

            long offset = timelibLookupAbbr(ptr, dst, tzAbbr, found);
            if (found[0] != 0) {
                t.zoneType = TIMELIB_ZONETYPE_ABBR;
                t.dst = dst[0];
                t.tzAbbr = tzAbbr[0].toUpperCase(java.util.Locale.ROOT);
            }

            if (found[0] == 0 || "UTC".equals(tzAbbr[0])) {
                int[] dummyError = new int[1];
                TimelibTzinfo res = tzdb.parseTzfile(tzAbbr[0], dummyError);
                if (res != null) {
                    t.tzInfo = res;
                    t.zoneType = TIMELIB_ZONETYPE_ID;
                    found[0]++;
                }
            }
            tzNotFound[0] = (found[0] == 0) ? 1 : 0;
            retval = offset;
        }
        while (parenCount > 0 && ptr.ch() == ')') {
            ptr.pos++;
            parenCount--;
        }
        return retval;
    }

    // ------------------------------------------------------------------ scanner loop & entry point

    /** C: {@code scan} */
    private static int scan(Scanner s) {
        int cursor = s.cur;

        while (true) {
            s.tok = cursor;
            s.len = 0;
            if (cursor >= s.lim) {
                s.cur = cursor;
                return EOI;
            }

            int bestLen = -1;
            Rule bestRule = null;
            for (int i = 0; i < RULES.length; i++) {
                Matcher m = s.matchers[i];
                m.region(cursor, s.lim);
                if (m.lookingAt()) {
                    int len = m.end() - cursor;
                    if (len > bestLen) {
                        bestLen = len;
                        bestRule = RULES[i];
                    }
                }
            }

            if (bestRule == null) {
                s.cur = cursor;
                return EOI;
            }

            cursor += bestLen;
            int tok = bestRule.action.execute(s, cursor);
            if (tok != LOOP_STD) {
                return tok;
            }
        }
    }

    private static TimelibTime newUnsetTime() {
        TimelibTime t = new TimelibTime();
        t.y = TIMELIB_UNSET;
        t.d = TIMELIB_UNSET;
        t.m = TIMELIB_UNSET;
        t.h = TIMELIB_UNSET;
        t.i = TIMELIB_UNSET;
        t.s = TIMELIB_UNSET;
        t.us = TIMELIB_UNSET;
        t.z = TIMELIB_UNSET;
        t.dst = TIMELIB_UNSET;
        t.isLocaltime = 0;
        t.zoneType = 0;
        t.relative.days = TIMELIB_UNSET;
        return t;
    }

    private static boolean isCWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == 0x0b;
    }

    /** C: {@code timelib_fill_holes} */
    public static void fillHoles(TimelibTime parsed, TimelibTime now, int options) {
        if ((options & TIMELIB_OVERRIDE_TIME) == 0 && parsed.haveDate != 0 && parsed.haveTime == 0) {
            parsed.h = 0;
            parsed.i = 0;
            parsed.s = 0;
            parsed.us = 0;
        }
        if (
            parsed.y != TIMELIB_UNSET || parsed.m != TIMELIB_UNSET || parsed.d != TIMELIB_UNSET ||
            parsed.h != TIMELIB_UNSET || parsed.i != TIMELIB_UNSET || parsed.s != TIMELIB_UNSET
        ) {
            if (parsed.us == TIMELIB_UNSET) parsed.us = 0;
        } else {
            if (parsed.us == TIMELIB_UNSET) parsed.us = (now != null && now.us != TIMELIB_UNSET) ? now.us : 0;
        }
        if (parsed.y == TIMELIB_UNSET) parsed.y = (now != null && now.y != TIMELIB_UNSET) ? now.y : 0;
        if (parsed.m == TIMELIB_UNSET) parsed.m = (now != null && now.m != TIMELIB_UNSET) ? now.m : 0;
        if (parsed.d == TIMELIB_UNSET) parsed.d = (now != null && now.d != TIMELIB_UNSET) ? now.d : 0;
        if (parsed.h == TIMELIB_UNSET) parsed.h = (now != null && now.h != TIMELIB_UNSET) ? now.h : 0;
        if (parsed.i == TIMELIB_UNSET) parsed.i = (now != null && now.i != TIMELIB_UNSET) ? now.i : 0;
        if (parsed.s == TIMELIB_UNSET) parsed.s = (now != null && now.s != TIMELIB_UNSET) ? now.s : 0;

        if (parsed.tzInfo == null && now != null) {
            parsed.tzInfo = now.tzInfo;

            if (parsed.z == TIMELIB_UNSET) parsed.z = (now.z != TIMELIB_UNSET) ? now.z : 0;
            if (parsed.dst == TIMELIB_UNSET) parsed.dst = (now.dst != TIMELIB_UNSET) ? now.dst : 0;

            if (parsed.tzAbbr == null) {
                parsed.tzAbbr = now.tzAbbr;
            }
        }

        if (parsed.zoneType == 0 && now != null && now.zoneType != 0) {
            parsed.zoneType = now.zoneType;
            parsed.isLocaltime = 1;
        }
    }

    /**
     * C: {@code timelib_strtotime}
     *
     * <p>Phase A wires the scanner framework and 3 example rules; Phase B adds full
     * {@code timelib_valid_time}/{@code timelib_valid_date} and Phase C adds all remaining rules.
     */
    @TruffleBoundary
    public static TimelibTime strtotime(String input, TimelibErrorContainer[] errorsOut, ZoneinfoDb tzdb) {
        Scanner in = new Scanner();
        in.errors = new TimelibErrorContainer();

        int s = 0;
        int e = input.length() - 1;
        if (!input.isEmpty()) {
            while (s <= e && isCWhitespace(input.charAt(s))) {
                s++;
            }
            while (e > s && isCWhitespace(input.charAt(e))) {
                e--;
            }
        }
        if (e - s < 0) {
            in.time = newUnsetTime();
            in.time.relative.days = 0;
            addError(in, TIMELIB_ERR_EMPTY_STRING, "Empty string");
            if (errorsOut != null) {
                errorsOut[0] = in.errors;
            }
            return in.time;
        }

        String trimmed = input.substring(s, e + 1);
        // Append one NUL byte for rules matching trailing [\000] (e.g. meridian, datenoyear);
        // lim = trimmed.length() + 1 so consuming it reaches EOI on the next std: step.
        in.str = trimmed + "\0";
        in.lim = in.str.length();
        in.cur = 0;
        in.time = newUnsetTime();
        in.tzdb = tzdb;
        in.matchers = new Matcher[RULES.length];
        for (int i = 0; i < RULES.length; i++) {
            in.matchers[i] = RULES[i].pattern.matcher(in.str);
        }

        int t;
        do {
            t = scan(in);
        } while (t != EOI);

        if (errorsOut != null) {
            errorsOut[0] = in.errors;
        }
        return in.time;
    }
}
