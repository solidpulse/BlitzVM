// Ported from timelib 2022.17 (ext/date/lib/parse_date.re),
// Copyright (c) 2015-2023 Derick Rethans, Copyright (c) 2018 MongoDB, Inc.,
// MIT License (see src/main/resources/META-INF/LICENSE-timelib).

package dev.phpruntime.timelib;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    private static final String ANY = g("[\\u0000-\\uffff]");

    private static final String NBSP = g("\\u00c2\\u00a0|\\u00a0");
    private static final String NNBSP = g("\\u00e2\\u0080\\u00af|\\u202f");
    private static final String SPACE = g("[ \\t]+|" + NBSP + "+|" + NNBSP + "+");
    private static final String FRAC = g("\\.[0-9]+");

    private static final String AGO = ci("ago");

    private static final String HOUR24 = g("2[0-4]|[01][0-9]|[0-9]");
    private static final String HOUR24LZ = g("2[0-4]|[01][0-9]");
    private static final String HOUR12 = g("1[0-2]|0?[1-9]");
    private static final String MINUTE = g("[0-5][0-9]|[0-9]");
    private static final String MINUTELZ = g("[0-5][0-9]");
    private static final String SECOND = g("60|" + MINUTE);
    private static final String SECONDLZ = g("60|" + MINUTELZ);
    private static final String MERIDIAN = g("([AaPp]\\.?[Mm]\\.?)[\u0000\\t ]");
    private static final String TZ = g("[A-Z][a-z]+(?:[_/-][A-Za-z]+)+|\\(?[A-Za-z]{1,6}\\)?");
    private static final String TZCORRECTION = g("(?:GMT)?[+-](?:(?:" + HOUR24LZ + ":" + MINUTELZ + ":" + SECONDLZ + ")|(?:" + HOUR24LZ + MINUTELZ + SECONDLZ + ")|(?:" + HOUR24 + "(?::?" + MINUTE + ")?))");

    private static final String DAYSUF = g("st|nd|rd|th");

    private static final String MONTH = g("1[0-2]|0?[0-9]");
    private static final String DAY = g("(?:3[01]|[12][0-9]|0?[0-9])" + DAYSUF + "?");
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
    private static final String DAYSPECIAL = g(ci("weekdays") + "|" + ci("weekday"));
    private static final String DAYTEXT = g(DAYFULLS + "|" + DAYFULL + "|" + DAYABBR + "|" + DAYSPECIAL);

    private static final String MONTHFULL = g(ci("january") + "|" + ci("february") + "|" + ci("march") + "|" + ci("april") + "|" + ci("may") + "|" + ci("june") + "|" + ci("july") + "|" + ci("august") + "|" + ci("september") + "|" + ci("october") + "|" + ci("november") + "|" + ci("december"));
    private static final String MONTHABBR = g(ci("jan") + "|" + ci("feb") + "|" + ci("mar") + "|" + ci("apr") + "|" + ci("may") + "|" + ci("jun") + "|" + ci("jul") + "|" + ci("aug") + "|" + ci("sept") + "|" + ci("sep") + "|" + ci("oct") + "|" + ci("nov") + "|" + ci("dec"));
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
    private static final String RELTEXTNUMBER = g(ci("first") + "|" + ci("second") + "|" + ci("third") + "|" + ci("fourth") + "|" + ci("fifth") + "|" + ci("sixth") + "|" + ci("seventh") + "|" + ci("eighth") + "|" + ci("eight") + "|" + ci("ninth") + "|" + ci("tenth") + "|" + ci("eleventh") + "|" + ci("twelfth"));
    private static final String RELTEXTTEXT = g(ci("previous") + "|" + ci("next") + "|" + ci("last") + "|" + ci("this"));
    private static final String RELTEXTUNIT = g(g(ci("millisecond") + "|" + ci("msec") + "|" + ci("microsecond") + "|" + ci("µsec") + "|" + ci("usec") + "|" + ci("second") + "|" + ci("sec") + "|" + ci("minute") + "|" + ci("min") + "|" + ci("fortnight") + "|" + ci("forthnight") + "|" + ci("month") + "|" + ci("year") + "|" + ci("hour") + "|" + ci("day")) + "[sS]?|" + ci("weeks") + "|" + ci("ms") + "|" + ci("µs") + "|" + DAYTEXT);

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
    /** The regex rules stay as a reference implementation: -Dblitz.date.regexScanner=true. */
    private static final boolean USE_DFA = !Boolean.getBoolean("blitz.date.regexScanner");
    private static final String PADDING = "\0".repeat(64);

    /** Of two scanner results, the longer match; on equal length the first (earlier rules). */
    private static long longer(long a, long b) {
        if (b == -1L) {
            return a;
        }
        return a == -1L || (int) b > (int) a ? b : a;
    }

    /**
     * timelib scans bytes: its grammar spells non-ASCII text (e.g. the micro sign) as UTF-8 bytes
     * and reports byte offsets. Until PHP strings are byte strings (task 08), give the scanner
     * the UTF-8 bytes of the input, one char per byte. TODO(task 08): remove; strings will
     * already be bytes.
     */
    private static String asBytes(String input) {
        for (int i = 0; i < input.length(); i++) {
            if (input.charAt(i) >= 0x80) {
                return new String(input.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        java.nio.charset.StandardCharsets.ISO_8859_1);
            }
        }
        return input;
    }

    /**
     * For each first character 0..255, the indices (in rule order) of the rules that can match
     * starting with it. Computed from the patterns themselves: a rule qualifies when it matches
     * the one-character input or would need more input ({@code hitEnd}). Trying only these rules
     * at a position gives the same longest match as trying all of them.
     */
    private static final int[][] RULES_BY_FIRST_CHAR = buildFirstCharDispatch();
    private static final int[] ALL_RULES = buildAllRules();

    private static int[][] buildFirstCharDispatch() {
        int[][] out = new int[256][];
        for (int c = 0; c < 256; c++) {
            String one = String.valueOf((char) c);
            int[] tmp = new int[RULES.length];
            int n = 0;
            for (int i = 0; i < RULES.length; i++) {
                Matcher m = RULES[i].pattern.matcher(one);
                if (m.lookingAt() || m.hitEnd()) {
                    tmp[n++] = i;
                }
            }
            out[c] = java.util.Arrays.copyOf(tmp, n);
        }
        return out;
    }

    private static int[] buildAllRules() {
        int[] all = new int[RULES.length];
        for (int i = 0; i < all.length; i++) {
            all[i] = i;
        }
        return all;
    }

    private static Rule[] buildRules() {
        List<Rule> r = new ArrayList<>(55);

        // 1: yesterday
        r.add(new Rule("yesterday", ci("yesterday"), (s, cursor) -> {
            timelibInit(s, cursor);
            timelibHaveRelative(s);
            timelibUnhaveTime(s);
            s.time.relative.d = -1;
            return TIMELIB_RELATIVE;
        }));

        // 2: now
        r.add(new Rule("now", ci("now"), (s, cursor) -> {
            timelibInit(s, cursor);
            return TIMELIB_RELATIVE;
        }));

        // 3: noon
        r.add(new Rule("noon", ci("noon"), (s, cursor) -> {
            timelibInit(s, cursor);
            timelibUnhaveTime(s);
            if (!timelibHaveTime(s)) return TIMELIB_ERROR;
            s.time.h = 12;
            return TIMELIB_RELATIVE;
        }));

        // 4: midnight | today
        r.add(new Rule("midnight | today", ci("midnight") + "|" + ci("today"), (s, cursor) -> {
            timelibInit(s, cursor);
            timelibUnhaveTime(s);
            return TIMELIB_RELATIVE;
        }));

        // 5: tomorrow
        r.add(new Rule("tomorrow", ci("tomorrow"), (s, cursor) -> {
            timelibInit(s, cursor);
            timelibHaveRelative(s);
            timelibUnhaveTime(s);
            s.time.relative.d = 1;
            return TIMELIB_RELATIVE;
        }));

        // 6: timestamp
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

        // 7: timestampms
        r.add(new Rule("timestampms", TIMESTAMPMS, (s, cursor) -> {
            String str = timelibInit(s, cursor);
            Cursor ptr = new Cursor(str);
            timelibHaveRelative(s);
            timelibUnhaveDate(s);
            timelibUnhaveTime(s);
            if (!timelibHaveTz(s, cursor)) {
                return TIMELIB_ERROR;
            }

            boolean isNegative = (ptr.ch(1) == '-');
            long i = timelibGetSignedNr(s, ptr, 24);
            int ptrBefore = ptr.pos;
            long us = timelibGetSignedNr(s, ptr, 6);
            us = (long) (us * Math.pow(10, 7 - (ptr.pos - ptrBefore)));
            if (isNegative) {
                us *= -1;
            }

            s.time.y = 1970;
            s.time.m = 1;
            s.time.d = 1;
            s.time.h = 0;
            s.time.i = 0;
            s.time.s = 0;
            s.time.us = 0;
            s.time.relative.s += i;
            s.time.relative.us = us;
            s.time.isLocaltime = 1;
            s.time.zoneType = TIMELIB_ZONETYPE_OFFSET;
            s.time.z = 0;
            s.time.dst = 0;

            return TIMELIB_RELATIVE;
        }));

        // 8: firstdayof | lastdayof
        r.add(new Rule("firstdayof | lastdayof", FIRSTDAYOF + "|" + LASTDAYOF, (s, cursor) -> {
            String str = timelibInit(s, cursor);
            Cursor ptr = new Cursor(str);
            timelibHaveRelative(s);
            if (ptr.ch() == 'l' || ptr.ch() == 'L') {
                s.time.relative.firstLastDayOf = TIMELIB_SPECIAL_LAST_DAY_OF_MONTH;
            } else {
                s.time.relative.firstLastDayOf = TIMELIB_SPECIAL_FIRST_DAY_OF_MONTH;
            }
            return TIMELIB_LF_DAY_OF_MONTH;
        }));

        // 9: backof | frontof
        r.add(new Rule("backof | frontof", BACKOF + "|" + FRONTOF, (s, cursor) -> {
            String str = timelibInit(s, cursor);
            Cursor ptr = new Cursor(str);
            timelibUnhaveTime(s);
            if (!timelibHaveTime(s)) return TIMELIB_ERROR;
            if (ptr.ch() == 'b' || ptr.ch() == 'B') {
                s.time.h = timelibGetNr(ptr, 2);
                s.time.i = 15;
            } else {
                s.time.h = timelibGetNr(ptr, 2) - 1;
                s.time.i = 45;
            }
            if (ptr.ch() != '\0') {
                timelibEatSpaces(ptr);
                s.time.h += timelibMeridian(ptr, s.time.h);
            }
            return TIMELIB_LF_DAY_OF_MONTH;
        }));

        // 10: weekdayof
        r.add(new Rule("weekdayof", WEEKDAYOF, (s, cursor) -> {
            String str = timelibInit(s, cursor);
            Cursor ptr = new Cursor(str);
            timelibHaveRelative(s);
            timelibHaveSpecialRelative(s);
            int[] behavior = new int[1];
            long i = timelibGetRelativeText(ptr, behavior);
            timelibEatSpaces(ptr);
            if (i > 0) {
                s.time.relative.special.type = TIMELIB_SPECIAL_DAY_OF_WEEK_IN_MONTH;
                timelibSetRelative(ptr, i, 1, s, TIMELIB_TIME_PART_DONT_KEEP);
            } else {
                s.time.relative.special.type = TIMELIB_SPECIAL_LAST_DAY_OF_WEEK_IN_MONTH;
                timelibSetRelative(ptr, i, behavior[0], s, TIMELIB_TIME_PART_DONT_KEEP);
            }
            return TIMELIB_WEEK_DAY_OF_MONTH;
        }));

        // 11: timetiny12 | timeshort12 | timelong12
        r.add(new Rule("timetiny12 | timeshort12 | timelong12",
                TIMELONG12 + "|" + TIMESHORT12 + "|" + TIMETINY12, (s, cursor) -> {
            String str = timelibInit(s, cursor);
            Cursor ptr = new Cursor(str);
            if (!timelibHaveTime(s)) return TIMELIB_ERROR;
            s.time.h = timelibGetNr(ptr, 2);
            if (ptr.ch() == ':' || ptr.ch() == '.') {
                s.time.i = timelibGetNr(ptr, 2);
                if (ptr.ch() == ':' || ptr.ch() == '.') {
                    s.time.s = timelibGetNr(ptr, 2);
                }
            }
            timelibEatSpaces(ptr);
            s.time.h += timelibMeridian(ptr, s.time.h);
            return TIMELIB_TIME12;
        }));

        // 12: mssqltime
        r.add(new Rule("mssqltime", MSSQLTIME, (s, cursor) -> {
            String str = timelibInit(s, cursor);
            Cursor ptr = new Cursor(str);
            if (!timelibHaveTime(s)) return TIMELIB_ERROR;
            s.time.h = timelibGetNr(ptr, 2);
            s.time.i = timelibGetNr(ptr, 2);
            if (ptr.ch() == ':' || ptr.ch() == '.') {
                s.time.s = timelibGetNr(ptr, 2);
                if (ptr.ch() == ':' || ptr.ch() == '.') {
                    s.time.us = timelibGetFracNr(ptr);
                }
            }
            timelibEatSpaces(ptr);
            s.time.h += timelibMeridian(ptr, s.time.h);
            return TIMELIB_TIME24_WITH_ZONE;
        }));

        // 13: timetiny24 | timeshort24 | timelong24 | iso8601long
        r.add(new Rule("timetiny24 | timeshort24 | timelong24 | iso8601long",
                ISO8601LONG + "|" + TIMELONG24 + "|" + TIMESHORT24 + "|" + TIMETINY24, (s, cursor) -> {
            String str = timelibInit(s, cursor);
            Cursor ptr = new Cursor(str);
            if (!timelibHaveTime(s)) return TIMELIB_ERROR;
            s.time.h = timelibGetNr(ptr, 2);
            if (ptr.ch() == ':' || ptr.ch() == '.') {
                s.time.i = timelibGetNr(ptr, 2);
                if (ptr.ch() == ':' || ptr.ch() == '.') {
                    s.time.s = timelibGetNr(ptr, 2);
                    if (ptr.ch() == '.') {
                        s.time.us = timelibGetFracNr(ptr);
                    }
                }
            }
            if (ptr.ch() != '\0') {
                int[] tzNotFound = new int[1];
                int[] dst = new int[1];
                s.time.z = timelibParseZone(ptr, dst, s.time, tzNotFound, s.tzdb);
                s.time.dst = dst[0];
                if (tzNotFound[0] != 0) {
                    addError(s, TIMELIB_ERR_TZID_NOT_FOUND, "The timezone could not be found in the database");
                }
            }
            return TIMELIB_TIME24_WITH_ZONE;
        }));

        // 14: gnunocolon
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

        // 15: iso8601nocolon
        r.add(new Rule("iso8601nocolon", ISO8601NOCOLON, (s, cursor) -> {
            String str = timelibInit(s, cursor);
            Cursor ptr = new Cursor(str);
            if (!timelibHaveTime(s)) return TIMELIB_ERROR;
            s.time.h = timelibGetNr(ptr, 2);
            s.time.i = timelibGetNr(ptr, 2);
            s.time.s = timelibGetNr(ptr, 2);
            if (ptr.ch() != '\0') {
                int[] tzNotFound = new int[1];
                int[] dst = new int[1];
                s.time.z = timelibParseZone(ptr, dst, s.time, tzNotFound, s.tzdb);
                s.time.dst = dst[0];
                if (tzNotFound[0] != 0) {
                    addError(s, TIMELIB_ERR_TZID_NOT_FOUND, "The timezone could not be found in the database");
                }
            }
            return TIMELIB_ISO_NOCOLON;
        }));

        // 16: americanshort | american
        r.add(new Rule("americanshort | american", AMERICAN + "|" + AMERICANSHORT, (s, cursor) -> {
            String str = timelibInit(s, cursor);
            Cursor ptr = new Cursor(str);
            if (!timelibHaveDate(s)) return TIMELIB_ERROR;
            s.time.m = timelibGetNr(ptr, 2);
            s.time.d = timelibGetNr(ptr, 2);
            if (ptr.ch() == '/') {
                int[] length = new int[1];
                s.time.y = timelibGetNrEx(ptr, 4, length);
                s.time.y = timelibProcessYear(s.time.y, length[0]);
            }
            return TIMELIB_AMERICAN;
        }));

        // 17: iso8601date4 | iso8601dateslash | dateslash
        r.add(new Rule("iso8601date4 | iso8601dateslash | dateslash",
                ISO8601DATE4 + "|" + ISO8601DATESLASH + "|" + DATESLASH, (s, cursor) -> {
            String str = timelibInit(s, cursor);
            Cursor ptr = new Cursor(str);
            if (!timelibHaveDate(s)) return TIMELIB_ERROR;
            s.time.y = timelibGetSignedNr(s, ptr, 4);
            s.time.m = timelibGetNr(ptr, 2);
            s.time.d = timelibGetNr(ptr, 2);
            return TIMELIB_ISO_DATE;
        }));

        // 18: iso8601date2
        r.add(new Rule("iso8601date2", ISO8601DATE2, (s, cursor) -> {
            String str = timelibInit(s, cursor);
            Cursor ptr = new Cursor(str);
            if (!timelibHaveDate(s)) return TIMELIB_ERROR;
            int[] length = new int[1];
            s.time.y = timelibGetNrEx(ptr, 4, length);
            s.time.m = timelibGetNr(ptr, 2);
            s.time.d = timelibGetNr(ptr, 2);
            s.time.y = timelibProcessYear(s.time.y, length[0]);
            return TIMELIB_ISO_DATE;
        }));

        // 19: iso8601datex
        r.add(new Rule("iso8601datex", ISO8601DATEX, (s, cursor) -> {
            String str = timelibInit(s, cursor);
            Cursor ptr = new Cursor(str);
            if (!timelibHaveDate(s)) return TIMELIB_ERROR;
            s.time.y = timelibGetSignedNr(s, ptr, 19);
            s.time.m = timelibGetNr(ptr, 2);
            s.time.d = timelibGetNr(ptr, 2);
            return TIMELIB_ISO_DATE;
        }));

        // 20: gnudateshorter
        r.add(new Rule("gnudateshorter", GNUDATESHORTER, (s, cursor) -> {
            String str = timelibInit(s, cursor);
            Cursor ptr = new Cursor(str);
            if (!timelibHaveDate(s)) return TIMELIB_ERROR;
            int[] length = new int[1];
            s.time.y = timelibGetNrEx(ptr, 4, length);
            s.time.m = timelibGetNr(ptr, 2);
            s.time.d = 1;
            s.time.y = timelibProcessYear(s.time.y, length[0]);
            return TIMELIB_ISO_DATE;
        }));

        // 21: gnudateshort
        r.add(new Rule("gnudateshort", GNUDATESHORT, (s, cursor) -> {
            String str = timelibInit(s, cursor);
            Cursor ptr = new Cursor(str);
            if (!timelibHaveDate(s)) return TIMELIB_ERROR;
            int[] length = new int[1];
            s.time.y = timelibGetNrEx(ptr, 4, length);
            s.time.m = timelibGetNr(ptr, 2);
            s.time.d = timelibGetNr(ptr, 2);
            s.time.y = timelibProcessYear(s.time.y, length[0]);
            return TIMELIB_ISO_DATE;
        }));

        // 22: datefull
        r.add(new Rule("datefull", DATEFULL, (s, cursor) -> {
            String str = timelibInit(s, cursor);
            Cursor ptr = new Cursor(str);
            if (!timelibHaveDate(s)) return TIMELIB_ERROR;
            s.time.d = timelibGetNr(ptr, 2);
            timelibSkipDaySuffix(ptr);
            s.time.m = timelibGetMonth(ptr);
            int[] length = new int[1];
            s.time.y = timelibGetNrEx(ptr, 4, length);
            s.time.y = timelibProcessYear(s.time.y, length[0]);
            return TIMELIB_DATE_FULL;
        }));

        // 23: pointeddate4
        r.add(new Rule("pointeddate4", POINTEDDATE4, (s, cursor) -> {
            String str = timelibInit(s, cursor);
            Cursor ptr = new Cursor(str);
            if (!timelibHaveDate(s)) return TIMELIB_ERROR;
            s.time.d = timelibGetNr(ptr, 2);
            s.time.m = timelibGetNr(ptr, 2);
            s.time.y = timelibGetNr(ptr, 4);
            return TIMELIB_DATE_FULL_POINTED;
        }));

        // 24: pointeddate2
        r.add(new Rule("pointeddate2", POINTEDDATE2, (s, cursor) -> {
            String str = timelibInit(s, cursor);
            Cursor ptr = new Cursor(str);
            if (!timelibHaveDate(s)) return TIMELIB_ERROR;
            s.time.d = timelibGetNr(ptr, 2);
            s.time.m = timelibGetNr(ptr, 2);
            int[] length = new int[1];
            s.time.y = timelibGetNrEx(ptr, 2, length);
            s.time.y = timelibProcessYear(s.time.y, length[0]);
            return TIMELIB_DATE_FULL_POINTED;
        }));

        // 25: datenoday
        r.add(new Rule("datenoday", DATENODAY, (s, cursor) -> {
            String str = timelibInit(s, cursor);
            Cursor ptr = new Cursor(str);
            if (!timelibHaveDate(s)) return TIMELIB_ERROR;
            s.time.m = timelibGetMonth(ptr);
            int[] length = new int[1];
            s.time.y = timelibGetNrEx(ptr, 4, length);
            s.time.d = 1;
            s.time.y = timelibProcessYear(s.time.y, length[0]);
            return TIMELIB_DATE_NO_DAY;
        }));

        // 26: datenodayrev
        r.add(new Rule("datenodayrev", DATENODAYREV, (s, cursor) -> {
            String str = timelibInit(s, cursor);
            Cursor ptr = new Cursor(str);
            if (!timelibHaveDate(s)) return TIMELIB_ERROR;
            int[] length = new int[1];
            s.time.y = timelibGetNrEx(ptr, 4, length);
            s.time.m = timelibGetMonth(ptr);
            s.time.d = 1;
            s.time.y = timelibProcessYear(s.time.y, length[0]);
            return TIMELIB_DATE_NO_DAY;
        }));

        // 27: datetextual | datenoyear
        r.add(new Rule("datetextual | datenoyear", DATETEXTUAL + "|" + DATENOYEAR, (s, cursor) -> {
            String str = timelibInit(s, cursor);
            Cursor ptr = new Cursor(str);
            if (!timelibHaveDate(s)) return TIMELIB_ERROR;
            s.time.m = timelibGetMonth(ptr);
            s.time.d = timelibGetNr(ptr, 2);
            int[] length = new int[1];
            s.time.y = timelibGetNrEx(ptr, 4, length);
            s.time.y = timelibProcessYear(s.time.y, length[0]);
            return TIMELIB_DATE_TEXT;
        }));

        // 28: datenoyearrev
        r.add(new Rule("datenoyearrev", DATENOYEARREV, (s, cursor) -> {
            String str = timelibInit(s, cursor);
            Cursor ptr = new Cursor(str);
            if (!timelibHaveDate(s)) return TIMELIB_ERROR;
            s.time.d = timelibGetNr(ptr, 2);
            timelibSkipDaySuffix(ptr);
            s.time.m = timelibGetMonth(ptr);
            return TIMELIB_DATE_TEXT;
        }));

        // 29: datenocolon
        r.add(new Rule("datenocolon", DATENOCOLON, (s, cursor) -> {
            String str = timelibInit(s, cursor);
            Cursor ptr = new Cursor(str);
            if (!timelibHaveDate(s)) return TIMELIB_ERROR;
            s.time.y = timelibGetNr(ptr, 4);
            s.time.m = timelibGetNr(ptr, 2);
            s.time.d = timelibGetNr(ptr, 2);
            return TIMELIB_DATE_NOCOLON;
        }));

        // 30: xmlrpc | xmlrpcnocolon | soap | wddx | exif
        r.add(new Rule("xmlrpc | xmlrpcnocolon | soap | wddx | exif",
                XMLRPC + "|" + XMLRPCNOCOLON + "|" + SOAP + "|" + WDDX + "|" + EXIF, (s, cursor) -> {
            String str = timelibInit(s, cursor);
            Cursor ptr = new Cursor(str);
            if (!timelibHaveTime(s)) return TIMELIB_ERROR;
            if (!timelibHaveDate(s)) return TIMELIB_ERROR;
            s.time.y = timelibGetNr(ptr, 4);
            s.time.m = timelibGetNr(ptr, 2);
            s.time.d = timelibGetNr(ptr, 2);
            s.time.h = timelibGetNr(ptr, 2);
            s.time.i = timelibGetNr(ptr, 2);
            s.time.s = timelibGetNr(ptr, 2);
            if (ptr.ch() == '.') {
                s.time.us = timelibGetFracNr(ptr);
                if (ptr.ch() != '\0') {
                    int[] tzNotFound = new int[1];
                    int[] dst = new int[1];
                    s.time.z = timelibParseZone(ptr, dst, s.time, tzNotFound, s.tzdb);
                    s.time.dst = dst[0];
                    if (tzNotFound[0] != 0) {
                        addError(s, TIMELIB_ERR_TZID_NOT_FOUND, "The timezone could not be found in the database");
                    }
                }
            }
            return TIMELIB_XMLRPC_SOAP;
        }));

        // 31: pgydotd
        r.add(new Rule("pgydotd", PGYDOTD, (s, cursor) -> {
            String str = timelibInit(s, cursor);
            Cursor ptr = new Cursor(str);
            if (!timelibHaveDate(s)) return TIMELIB_ERROR;
            int[] length = new int[1];
            s.time.y = timelibGetNrEx(ptr, 4, length);
            s.time.d = timelibGetNr(ptr, 3);
            s.time.m = 1;
            s.time.y = timelibProcessYear(s.time.y, length[0]);
            return TIMELIB_PG_YEARDAY;
        }));

        // 32: isoweekday
        r.add(new Rule("isoweekday", ISOWEEKDAY, (s, cursor) -> {
            String str = timelibInit(s, cursor);
            Cursor ptr = new Cursor(str);
            if (!timelibHaveDate(s)) return TIMELIB_ERROR;
            timelibHaveRelative(s);
            s.time.y = timelibGetNr(ptr, 4);
            long w = timelibGetNr(ptr, 2);
            long d = timelibGetNr(ptr, 1);
            s.time.m = 1;
            s.time.d = 1;
            s.time.relative.d = Dow.daynrFromWeeknr(s.time.y, w, d);
            return TIMELIB_ISO_WEEK;
        }));

        // 33: isoweek
        r.add(new Rule("isoweek", ISOWEEK, (s, cursor) -> {
            String str = timelibInit(s, cursor);
            Cursor ptr = new Cursor(str);
            if (!timelibHaveDate(s)) return TIMELIB_ERROR;
            timelibHaveRelative(s);
            s.time.y = timelibGetNr(ptr, 4);
            long w = timelibGetNr(ptr, 2);
            long d = 1;
            s.time.m = 1;
            s.time.d = 1;
            s.time.relative.d = Dow.daynrFromWeeknr(s.time.y, w, d);
            return TIMELIB_ISO_WEEK;
        }));

        // 34: pgtextshort
        r.add(new Rule("pgtextshort", PGTEXTSHORT, (s, cursor) -> {
            String str = timelibInit(s, cursor);
            Cursor ptr = new Cursor(str);
            if (!timelibHaveDate(s)) return TIMELIB_ERROR;
            s.time.m = timelibGetMonth(ptr);
            s.time.d = timelibGetNr(ptr, 2);
            int[] length = new int[1];
            s.time.y = timelibGetNrEx(ptr, 4, length);
            s.time.y = timelibProcessYear(s.time.y, length[0]);
            return TIMELIB_PG_TEXT;
        }));

        // 35: pgtextreverse
        r.add(new Rule("pgtextreverse", PGTEXTREVERSE, (s, cursor) -> {
            String str = timelibInit(s, cursor);
            Cursor ptr = new Cursor(str);
            if (!timelibHaveDate(s)) return TIMELIB_ERROR;
            int[] length = new int[1];
            s.time.y = timelibGetNrEx(ptr, 4, length);
            s.time.m = timelibGetMonth(ptr);
            s.time.d = timelibGetNr(ptr, 2);
            s.time.y = timelibProcessYear(s.time.y, length[0]);
            return TIMELIB_PG_TEXT;
        }));

        // 36: clf
        r.add(new Rule("clf", CLF, (s, cursor) -> {
            String str = timelibInit(s, cursor);
            Cursor ptr = new Cursor(str);
            if (!timelibHaveTime(s)) return TIMELIB_ERROR;
            if (!timelibHaveDate(s)) return TIMELIB_ERROR;
            s.time.d = timelibGetNr(ptr, 2);
            s.time.m = timelibGetMonth(ptr);
            s.time.y = timelibGetNr(ptr, 4);
            s.time.h = timelibGetNr(ptr, 2);
            s.time.i = timelibGetNr(ptr, 2);
            s.time.s = timelibGetNr(ptr, 2);
            timelibEatSpaces(ptr);
            int[] tzNotFound = new int[1];
            int[] dst = new int[1];
            s.time.z = timelibParseZone(ptr, dst, s.time, tzNotFound, s.tzdb);
            s.time.dst = dst[0];
            if (tzNotFound[0] != 0) {
                addError(s, TIMELIB_ERR_TZID_NOT_FOUND, "The timezone could not be found in the database");
            }
            return TIMELIB_CLF;
        }));

        // 37: year4
        r.add(new Rule("year4", YEAR4, (s, cursor) -> {
            String str = timelibInit(s, cursor);
            Cursor ptr = new Cursor(str);
            s.time.y = timelibGetNr(ptr, 4);
            return TIMELIB_CLF;
        }));

        // 38: ago
        r.add(new Rule("ago", AGO, (s, cursor) -> {
            timelibInit(s, cursor);
            s.time.relative.y = -s.time.relative.y;
            s.time.relative.m = -s.time.relative.m;
            s.time.relative.d = -s.time.relative.d;
            s.time.relative.h = -s.time.relative.h;
            s.time.relative.i = -s.time.relative.i;
            s.time.relative.s = -s.time.relative.s;
            s.time.relative.weekday = -s.time.relative.weekday;
            if (s.time.relative.weekday == 0) {
                s.time.relative.weekday = -7;
            }
            if (s.time.relative.haveSpecialRelative != 0 && s.time.relative.special.type == TIMELIB_SPECIAL_WEEKDAY) {
                s.time.relative.special.amount = -s.time.relative.special.amount;
            }
            return TIMELIB_AGO;
        }));

        // 39: daytext
        r.add(new Rule("daytext", DAYTEXT, (s, cursor) -> {
            String str = timelibInit(s, cursor);
            Cursor ptr = new Cursor(str);
            timelibHaveRelative(s);
            timelibHaveWeekdayRelative(s);
            timelibUnhaveTime(s);
            RelunitEntry relunit = timelibLookupRelunit(ptr);
            if (relunit != null) {
                s.time.relative.weekday = relunit.multiplier;
            }
            if (s.time.relative.weekdayBehavior != 2) {
                s.time.relative.weekdayBehavior = 1;
            }
            return TIMELIB_WEEKDAY;
        }));

        // 40: relativetextweek
        r.add(new Rule("relativetextweek", RELATIVETEXTWEEK, (s, cursor) -> {
            String str = timelibInit(s, cursor);
            Cursor ptr = new Cursor(str);
            timelibHaveRelative(s);
            int[] behavior = new int[1];
            while (ptr.ch() != '\0') {
                int oldPos = ptr.pos;
                long i = timelibGetRelativeText(ptr, behavior);
                timelibEatSpaces(ptr);
                timelibSetRelative(ptr, i, behavior[0], s, TIMELIB_TIME_PART_DONT_KEEP);
                s.time.relative.weekdayBehavior = 2;
                if (s.time.relative.haveWeekdayRelative == 0) {
                    timelibHaveWeekdayRelative(s);
                    s.time.relative.weekday = 1;
                }
                if (ptr.pos == oldPos) break;
            }
            return TIMELIB_RELATIVE;
        }));

        // 41: relativetext
        r.add(new Rule("relativetext", RELATIVETEXT, (s, cursor) -> {
            String str = timelibInit(s, cursor);
            Cursor ptr = new Cursor(str);
            timelibHaveRelative(s);
            int[] behavior = new int[1];
            while (ptr.ch() != '\0') {
                int oldPos = ptr.pos;
                long i = timelibGetRelativeText(ptr, behavior);
                timelibEatSpaces(ptr);
                timelibSetRelative(ptr, i, behavior[0], s, TIMELIB_TIME_PART_DONT_KEEP);
                if (ptr.pos == oldPos) break;
            }
            return TIMELIB_RELATIVE;
        }));

        // 42: monthfull | monthabbr
        r.add(new Rule("monthfull | monthabbr", MONTHFULL + "|" + MONTHABBR, (s, cursor) -> {
            String str = timelibInit(s, cursor);
            Cursor ptr = new Cursor(str);
            if (!timelibHaveDate(s)) return TIMELIB_ERROR;
            s.time.m = timelibLookupMonth(ptr);
            return TIMELIB_DATE_TEXT;
        }));

        // 43: tzcorrection | tz
        r.add(new Rule("tzcorrection | tz", TZCORRECTION + "|" + TZ, (s, cursor) -> {
            String str = timelibInit(s, cursor);
            Cursor ptr = new Cursor(str);
            if (!timelibHaveTz(s, cursor)) return TIMELIB_ERROR;
            timelibEatSpaces(ptr);
            int[] tzNotFound = new int[1];
            int[] dst = new int[1];
            s.time.z = timelibParseZone(ptr, dst, s.time, tzNotFound, s.tzdb);
            s.time.dst = dst[0];
            if (tzNotFound[0] != 0) {
                addError(s, TIMELIB_ERR_TZID_NOT_FOUND, "The timezone could not be found in the database");
            }
            return TIMELIB_TIMEZONE;
        }));

        // 44: dateshortwithtimeshort12 | dateshortwithtimelong12
        r.add(new Rule("dateshortwithtimeshort12 | dateshortwithtimelong12",
                DATESHORTWITHTIMELONG12 + "|" + DATESHORTWITHTIMESHORT12, (s, cursor) -> {
            String str = timelibInit(s, cursor);
            Cursor ptr = new Cursor(str);
            if (!timelibHaveDate(s)) return TIMELIB_ERROR;
            s.time.m = timelibGetMonth(ptr);
            s.time.d = timelibGetNr(ptr, 2);

            if (!timelibHaveTime(s)) return TIMELIB_ERROR;
            s.time.h = timelibGetNr(ptr, 2);
            s.time.i = timelibGetNr(ptr, 2);
            if (ptr.ch() == ':' || ptr.ch() == '.') {
                s.time.s = timelibGetNr(ptr, 2);
                if (ptr.ch() == '.') {
                    s.time.us = timelibGetFracNr(ptr);
                }
            }
            s.time.h += timelibMeridian(ptr, s.time.h);
            return TIMELIB_SHORTDATE_WITH_TIME;
        }));

        // 45: dateshortwithtimeshort | dateshortwithtimelong | dateshortwithtimelongtz
        r.add(new Rule("dateshortwithtimeshort | dateshortwithtimelong | dateshortwithtimelongtz",
                DATESHORTWITHTIMELONGTZ + "|" + DATESHORTWITHTIMELONG + "|" + DATESHORTWITHTIMESHORT, (s, cursor) -> {
            String str = timelibInit(s, cursor);
            Cursor ptr = new Cursor(str);
            if (!timelibHaveDate(s)) return TIMELIB_ERROR;
            s.time.m = timelibGetMonth(ptr);
            s.time.d = timelibGetNr(ptr, 2);

            if (!timelibHaveTime(s)) return TIMELIB_ERROR;
            s.time.h = timelibGetNr(ptr, 2);
            s.time.i = timelibGetNr(ptr, 2);
            if (ptr.ch() == ':') {
                s.time.s = timelibGetNr(ptr, 2);
                if (ptr.ch() == '.') {
                    s.time.us = timelibGetFracNr(ptr);
                }
            }
            if (ptr.ch() != '\0') {
                int[] tzNotFound = new int[1];
                int[] dst = new int[1];
                s.time.z = timelibParseZone(ptr, dst, s.time, tzNotFound, s.tzdb);
                s.time.dst = dst[0];
                if (tzNotFound[0] != 0) {
                    addError(s, TIMELIB_ERR_TZID_NOT_FOUND, "The timezone could not be found in the database");
                }
            }
            return TIMELIB_SHORTDATE_WITH_TIME;
        }));

        // 46: relative
        r.add(new Rule("relative", RELATIVE, (s, cursor) -> {
            String str = timelibInit(s, cursor);
            Cursor ptr = new Cursor(str);
            timelibHaveRelative(s);
            while (ptr.ch() != '\0') {
                int oldPos = ptr.pos;
                long i = timelibGetSignedNr(s, ptr, 24);
                timelibEatSpaces(ptr);
                timelibSetRelative(ptr, i, 1, s, TIMELIB_TIME_PART_KEEP);
                if (ptr.pos == oldPos) break;
            }
            return TIMELIB_RELATIVE;
        }));

        // 47: [.,]
        r.add(new Rule("[.,]", "[.,]", (s, cursor) -> {
            s.cur = cursor;
            return LOOP_STD;
        }));

        // 48: space
        r.add(new Rule("space", SPACE, (s, cursor) -> {
            s.cur = cursor;
            return LOOP_STD;
        }));

        // 49: nul_or_nl
        r.add(new Rule("nul_or_nl", "\\u0000|\\n", (s, cursor) -> {
            s.cur = cursor;
            s.pos = cursor;
            s.line++;
            return LOOP_STD;
        }));

        // 50: any
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

    public static void timelibSkipDaySuffix(Cursor ptr) {
        if (Character.isWhitespace(ptr.ch())) {
            return;
        }
        char c0 = Character.toLowerCase(ptr.ch(0));
        char c1 = Character.toLowerCase(ptr.ch(1));
        if ((c0 == 'n' && c1 == 'd')
                || (c0 == 'r' && c1 == 'd')
                || (c0 == 's' && c1 == 't')
                || (c0 == 't' && c1 == 'h')) {
            ptr.pos += 2;
        }
    }

    public static long timelibGetFracNr(Cursor ptr) {
        while (ptr.ch() != '.' && ptr.ch() != ':' && (ptr.ch() < '0' || ptr.ch() > '9')) {
            if (ptr.ch() == '\0') {
                return TIMELIB_UNSET;
            }
            ptr.pos++;
        }
        int begin = ptr.pos;
        while (ptr.ch() == '.' || ptr.ch() == ':' || (ptr.ch() >= '0' && ptr.ch() <= '9')) {
            ptr.pos++;
        }
        int end = ptr.pos;
        if (end <= begin + 1) {
            return TIMELIB_UNSET;
        }
        String digits = ptr.s.substring(begin + 1, end);
        try {
            double val = Double.parseDouble(digits);
            return (long) (val * Math.pow(10, 7 - (end - begin)));
        } catch (NumberFormatException e) {
            return TIMELIB_UNSET;
        }
    }

    public static void timelibEatSpaces(Cursor ptr) {
        while (true) {
            char c = ptr.ch();
            if (c == ' ' || c == '\t' || c == '\u00a0' || c == '\u202f') {
                ptr.pos++;
                continue;
            }
            if (c == '\u00c2' && ptr.ch(1) == '\u00a0') {
                ptr.pos += 2;
                continue;
            }
            if (c == '\u00e2' && ptr.ch(1) == '\u0080' && ptr.ch(2) == '\u00af') {
                ptr.pos += 3;
                continue;
            }
            break;
        }
    }

    public static long timelibMeridian(Cursor ptr, long h) {
        long retval = 0;
        while (ptr.ch() != 'A' && ptr.ch() != 'a' && ptr.ch() != 'P' && ptr.ch() != 'p' && ptr.ch() != '\0') {
            ptr.pos++;
        }
        if (ptr.ch() == 'a' || ptr.ch() == 'A') {
            if (h == 12) {
                retval = -12;
            }
        } else if (h != 12) {
            retval = 12;
        }
        if (ptr.ch() != '\0') ptr.pos++;
        if (ptr.ch() == '.') ptr.pos++;
        if (ptr.ch() == 'M' || ptr.ch() == 'm') ptr.pos++;
        if (ptr.ch() == '.') ptr.pos++;
        return retval;
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
    public static int timelibParseTzCor(Cursor ptr, int[] tzNotFound) {
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
                return sHour(tmp / 10000) + sMin((tmp / 100) % 100) + (int) (tmp % 100);
            }
            case 8:
                if (ptr.s.charAt(begin + 2) != ':' || ptr.s.charAt(begin + 5) != ':') {
                    break;
                }
                tzNotFound[0] = 0;
                return sHour(strtolPrefix(ptr.s, begin)) + sMin(strtolPrefix(ptr.s, begin + 3))
                        + (int) strtolPrefix(ptr.s, begin + 6);
        }
        return 0;
    }

    private static final int MAX_ABBR_LEN = 6;

    /** C: {@code timelib_lookup_abbr} */
    private static int timelibLookupAbbr(Cursor ptr, int[] dst, String[] tzAbbr, int[] found) {
        int begin = ptr.pos;
        while ((ptr.ch() >= 'A' && ptr.ch() <= 'Z')
                || (ptr.ch() >= 'a' && ptr.ch() <= 'z')
                || (ptr.ch() >= '0' && ptr.ch() <= '9')
                || ptr.ch() == '/' || ptr.ch() == '_' || ptr.ch() == '-' || ptr.ch() == '+') {
            ptr.pos++;
        }
        int end = ptr.pos;
        String word = ptr.s.substring(begin, end);
        int value = 0;
        int len = end - begin;
        if (len < MAX_ABBR_LEN) {
            TimezoneMap.Entry tp = TimezoneMap.abbrSearch(word, -1, 0);
            if (tp != null) {
                value = tp.gmtoffset;
                dst[0] = tp.type;
                value -= tp.type * 3600;
                found[0] = 1;
            } else {
                found[0] = 0;
            }
        } else {
            found[0] = 0;
        }
        tzAbbr[0] = word;
        return value;
    }

    /** C: {@code timelib_parse_zone} */
    public static int timelibParseZone(Cursor ptr, int[] dst, TimelibTime t, int[] tzNotFound, ZoneinfoDb tzdb) {
        int retval = 0;
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

            int offset = timelibLookupAbbr(ptr, dst, tzAbbr, found);
            if (found[0] != 0) {
                t.zoneType = TIMELIB_ZONETYPE_ABBR;
                t.dst = dst[0];
                t.tzAbbr = tzAbbr[0].toUpperCase(Locale.ROOT);
            }

            if (found[0] == 0 || "UTC".equalsIgnoreCase(tzAbbr[0])) {
                int[] dummyError = new int[1];
                TimelibTzinfo res = (tzdb != null) ? tzdb.parseTzfile(tzAbbr[0], dummyError) : null;
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

    // ------------------------------------------------------------------ tables & relative parsing

    private static final class RelunitEntry {
        final String name;
        final int unit;
        final int multiplier;

        RelunitEntry(String name, int unit, int multiplier) {
            this.name = name;
            this.unit = unit;
            this.multiplier = multiplier;
        }
    }

    private static final RelunitEntry[] TIMELIB_RELUNIT_LOOKUP = new RelunitEntry[] {
            new RelunitEntry("ms",           TIMELIB_MICROSEC, 1000),
            new RelunitEntry("msec",         TIMELIB_MICROSEC, 1000),
            new RelunitEntry("msecs",        TIMELIB_MICROSEC, 1000),
            new RelunitEntry("millisecond",  TIMELIB_MICROSEC, 1000),
            new RelunitEntry("milliseconds", TIMELIB_MICROSEC, 1000),
            new RelunitEntry("µs",           TIMELIB_MICROSEC,    1),
            new RelunitEntry("usec",         TIMELIB_MICROSEC,    1),
            new RelunitEntry("usecs",        TIMELIB_MICROSEC,    1),
            new RelunitEntry("µsec",         TIMELIB_MICROSEC,    1),
            new RelunitEntry("µsecs",        TIMELIB_MICROSEC,    1),
            new RelunitEntry("microsecond",  TIMELIB_MICROSEC,    1),
            new RelunitEntry("microseconds", TIMELIB_MICROSEC,    1),
            new RelunitEntry("sec",          TIMELIB_SECOND,   1),
            new RelunitEntry("secs",         TIMELIB_SECOND,   1),
            new RelunitEntry("second",       TIMELIB_SECOND,   1),
            new RelunitEntry("seconds",      TIMELIB_SECOND,   1),
            new RelunitEntry("min",          TIMELIB_MINUTE,   1),
            new RelunitEntry("mins",         TIMELIB_MINUTE,   1),
            new RelunitEntry("minute",       TIMELIB_MINUTE,   1),
            new RelunitEntry("minutes",      TIMELIB_MINUTE,   1),
            new RelunitEntry("hour",         TIMELIB_HOUR,     1),
            new RelunitEntry("hours",        TIMELIB_HOUR,     1),
            new RelunitEntry("day",          TIMELIB_DAY,      1),
            new RelunitEntry("days",         TIMELIB_DAY,      1),
            new RelunitEntry("week",         TIMELIB_DAY,      7),
            new RelunitEntry("weeks",        TIMELIB_DAY,      7),
            new RelunitEntry("fortnight",    TIMELIB_DAY,     14),
            new RelunitEntry("fortnights",   TIMELIB_DAY,     14),
            new RelunitEntry("forthnight",   TIMELIB_DAY,     14),
            new RelunitEntry("forthnights",  TIMELIB_DAY,     14),
            new RelunitEntry("month",        TIMELIB_MONTH,    1),
            new RelunitEntry("months",       TIMELIB_MONTH,    1),
            new RelunitEntry("year",         TIMELIB_YEAR,     1),
            new RelunitEntry("years",        TIMELIB_YEAR,     1),

            new RelunitEntry("mondays",      TIMELIB_WEEKDAY,  1),
            new RelunitEntry("monday",       TIMELIB_WEEKDAY,  1),
            new RelunitEntry("mon",          TIMELIB_WEEKDAY,  1),
            new RelunitEntry("tuesdays",     TIMELIB_WEEKDAY,  2),
            new RelunitEntry("tuesday",      TIMELIB_WEEKDAY,  2),
            new RelunitEntry("tue",          TIMELIB_WEEKDAY,  2),
            new RelunitEntry("wednesdays",   TIMELIB_WEEKDAY,  3),
            new RelunitEntry("wednesday",    TIMELIB_WEEKDAY,  3),
            new RelunitEntry("wed",          TIMELIB_WEEKDAY,  3),
            new RelunitEntry("thursdays",    TIMELIB_WEEKDAY,  4),
            new RelunitEntry("thursday",     TIMELIB_WEEKDAY,  4),
            new RelunitEntry("thu",          TIMELIB_WEEKDAY,  4),
            new RelunitEntry("fridays",      TIMELIB_WEEKDAY,  5),
            new RelunitEntry("friday",       TIMELIB_WEEKDAY,  5),
            new RelunitEntry("fri",          TIMELIB_WEEKDAY,  5),
            new RelunitEntry("saturdays",    TIMELIB_WEEKDAY,  6),
            new RelunitEntry("saturday",     TIMELIB_WEEKDAY,  6),
            new RelunitEntry("sat",          TIMELIB_WEEKDAY,  6),
            new RelunitEntry("sundays",      TIMELIB_WEEKDAY,  0),
            new RelunitEntry("sunday",       TIMELIB_WEEKDAY,  0),
            new RelunitEntry("sun",          TIMELIB_WEEKDAY,  0),

            new RelunitEntry("weekday",      TIMELIB_SPECIAL,  TIMELIB_SPECIAL_WEEKDAY),
            new RelunitEntry("weekdays",     TIMELIB_SPECIAL,  TIMELIB_SPECIAL_WEEKDAY)
    };

    public static RelunitEntry timelibLookupRelunit(Cursor ptr) {
        int begin = ptr.pos;
        while (ptr.ch() != '\0' && ptr.ch() != ' ' && ptr.ch() != ',' && ptr.ch() != '\t' && ptr.ch() != ';' && ptr.ch() != ':' &&
                ptr.ch() != '/' && ptr.ch() != '.' && ptr.ch() != '-' && ptr.ch() != '(' && ptr.ch() != ')') {
            ptr.pos++;
        }
        int end = ptr.pos;
        String word = ptr.s.substring(begin, end);
        for (RelunitEntry tp : TIMELIB_RELUNIT_LOOKUP) {
            if (word.equalsIgnoreCase(tp.name)) {
                return tp;
            }
        }
        return null;
    }

    private static final class ReltextEntry {
        final String name;
        final int type;
        final int value;

        ReltextEntry(String name, int type, int value) {
            this.name = name;
            this.type = type;
            this.value = value;
        }
    }

    private static final ReltextEntry[] TIMELIB_RELTEXT_LOOKUP = new ReltextEntry[] {
            new ReltextEntry("first",    0,  1),
            new ReltextEntry("next",     0,  1),
            new ReltextEntry("second",   0,  2),
            new ReltextEntry("third",    0,  3),
            new ReltextEntry("fourth",   0,  4),
            new ReltextEntry("fifth",    0,  5),
            new ReltextEntry("sixth",    0,  6),
            new ReltextEntry("seventh",  0,  7),
            new ReltextEntry("eight",    0,  8),
            new ReltextEntry("eighth",   0,  8),
            new ReltextEntry("ninth",    0,  9),
            new ReltextEntry("tenth",    0, 10),
            new ReltextEntry("eleventh", 0, 11),
            new ReltextEntry("twelfth",  0, 12),
            new ReltextEntry("last",     0, -1),
            new ReltextEntry("previous", 0, -1),
            new ReltextEntry("this",     1,  0)
    };

    public static long timelibLookupRelativeText(Cursor ptr, int[] behavior) {
        int begin = ptr.pos;
        while ((ptr.ch() >= 'A' && ptr.ch() <= 'Z') || (ptr.ch() >= 'a' && ptr.ch() <= 'z')) {
            ptr.pos++;
        }
        int end = ptr.pos;
        String word = ptr.s.substring(begin, end);
        long value = 0;
        for (ReltextEntry tp : TIMELIB_RELTEXT_LOOKUP) {
            if (word.equalsIgnoreCase(tp.name)) {
                value = tp.value;
                behavior[0] = tp.type;
            }
        }
        return value;
    }

    public static long timelibGetRelativeText(Cursor ptr, int[] behavior) {
        while (ptr.ch() == ' ' || ptr.ch() == '\t' || ptr.ch() == '-' || ptr.ch() == '/') {
            ptr.pos++;
        }
        return timelibLookupRelativeText(ptr, behavior);
    }

    private static final class MonthEntry {
        final String name;
        final int value;

        MonthEntry(String name, int value) {
            this.name = name;
            this.value = value;
        }
    }

    private static final MonthEntry[] TIMELIB_MONTH_LOOKUP = new MonthEntry[] {
            new MonthEntry("jan", 1),
            new MonthEntry("feb", 2),
            new MonthEntry("mar", 3),
            new MonthEntry("apr", 4),
            new MonthEntry("may", 5),
            new MonthEntry("jun", 6),
            new MonthEntry("jul", 7),
            new MonthEntry("aug", 8),
            new MonthEntry("sep", 9),
            new MonthEntry("sept", 9),
            new MonthEntry("oct", 10),
            new MonthEntry("nov", 11),
            new MonthEntry("dec", 12),
            new MonthEntry("i", 1),
            new MonthEntry("ii", 2),
            new MonthEntry("iii", 3),
            new MonthEntry("iv", 4),
            new MonthEntry("v", 5),
            new MonthEntry("vi", 6),
            new MonthEntry("vii", 7),
            new MonthEntry("viii", 8),
            new MonthEntry("ix", 9),
            new MonthEntry("x", 10),
            new MonthEntry("xi", 11),
            new MonthEntry("xii", 12),

            new MonthEntry("january", 1),
            new MonthEntry("february", 2),
            new MonthEntry("march", 3),
            new MonthEntry("april", 4),
            new MonthEntry("may", 5),
            new MonthEntry("june", 6),
            new MonthEntry("july", 7),
            new MonthEntry("august", 8),
            new MonthEntry("september", 9),
            new MonthEntry("october", 10),
            new MonthEntry("november", 11),
            new MonthEntry("december", 12)
    };

    public static long timelibLookupMonth(Cursor ptr) {
        int begin = ptr.pos;
        while ((ptr.ch() >= 'A' && ptr.ch() <= 'Z') || (ptr.ch() >= 'a' && ptr.ch() <= 'z')) {
            ptr.pos++;
        }
        int end = ptr.pos;
        String word = ptr.s.substring(begin, end);
        long value = 0;
        for (MonthEntry tp : TIMELIB_MONTH_LOOKUP) {
            if (word.equalsIgnoreCase(tp.name)) {
                value = tp.value;
            }
        }
        return value;
    }

    public static long timelibGetMonth(Cursor ptr) {
        while (ptr.ch() == ' ' || ptr.ch() == '\t' || ptr.ch() == '-' || ptr.ch() == '.' || ptr.ch() == '/') {
            ptr.pos++;
        }
        return timelibLookupMonth(ptr);
    }

    private static long addWithOverflow(Scanner s, long current, long amount, int multiplier) {
        try {
            long prod = Math.multiplyExact(amount, (long) multiplier);
            return Math.addExact(current, prod);
        } catch (ArithmeticException e) {
            addError(s, TIMELIB_ERR_NUMBER_OUT_OF_RANGE, "Number out of range");
            return current + (amount * multiplier);
        }
    }

    /** C: {@code timelib_set_relative} */
    public static void timelibSetRelative(Cursor ptr, long amount, int behavior, Scanner s, int timePart) {
        RelunitEntry relunit = timelibLookupRelunit(ptr);
        if (relunit == null) {
            return;
        }

        switch (relunit.unit) {
            case TIMELIB_MICROSEC: s.time.relative.us = addWithOverflow(s, s.time.relative.us, amount, relunit.multiplier); break;
            case TIMELIB_SECOND:   s.time.relative.s = addWithOverflow(s, s.time.relative.s, amount, relunit.multiplier); break;
            case TIMELIB_MINUTE:   s.time.relative.i = addWithOverflow(s, s.time.relative.i, amount, relunit.multiplier); break;
            case TIMELIB_HOUR:     s.time.relative.h = addWithOverflow(s, s.time.relative.h, amount, relunit.multiplier); break;
            case TIMELIB_DAY:      s.time.relative.d = addWithOverflow(s, s.time.relative.d, amount, relunit.multiplier); break;
            case TIMELIB_MONTH:    s.time.relative.m = addWithOverflow(s, s.time.relative.m, amount, relunit.multiplier); break;
            case TIMELIB_YEAR:     s.time.relative.y = addWithOverflow(s, s.time.relative.y, amount, relunit.multiplier); break;

            case TIMELIB_WEEKDAY:
                timelibHaveWeekdayRelative(s);
                if (timePart != TIMELIB_TIME_PART_KEEP) {
                    timelibUnhaveTime(s);
                }
                s.time.relative.d += (amount > 0 ? amount - 1 : amount) * 7;
                s.time.relative.weekday = relunit.multiplier;
                s.time.relative.weekdayBehavior = behavior;
                break;

            case TIMELIB_SPECIAL:
                timelibHaveSpecialRelative(s);
                if (timePart != TIMELIB_TIME_PART_KEEP) {
                    timelibUnhaveTime(s);
                }
                s.time.relative.special.type = relunit.multiplier;
                s.time.relative.special.amount = amount;
                break;
        }
    }

    public static String timelibTimezoneIdFromAbbr(String abbr, long gmtoffset, int isdst) {
        TimezoneMap.Entry tp = TimezoneMap.abbrSearch(abbr, gmtoffset, isdst);
        return tp != null ? tp.fullTzName : null;
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
            if (USE_DFA) {
                // Generated re2c scanners over consecutive rule groups (scripts/date-scanner):
                // the longest match wins and an equal-length match keeps the earlier group,
                // which is timelib's longest match with ties to the earlier rule.
                long r = DateScanner0.scan(s.str, cursor);
                r = longer(r, DateScanner1.scan(s.str, cursor));
                r = longer(r, DateScanner2.scan(s.str, cursor));
                r = longer(r, DateScanner3.scan(s.str, cursor));
                if (r != -1L && (int) r <= s.lim) {
                    bestRule = RULES[(int) (r >>> 32)];
                    bestLen = (int) r - cursor;
                }
            }
            if (bestRule == null) {
            char first = s.str.charAt(cursor);
            int[] candidates = first < 256 ? RULES_BY_FIRST_CHAR[first] : ALL_RULES;
            for (int i : candidates) {
                Matcher m = s.matchers[i];
                if (m == null) {
                    m = RULES[i].pattern.matcher(s.str);
                    s.matchers[i] = m;
                }
                m.region(cursor, s.lim);
                if (m.lookingAt()) {
                    int len = m.end() - cursor;
                    if (len > bestLen) {
                        bestLen = len;
                        bestRule = RULES[i];
                    }
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
     */
    @TruffleBoundary
    public static TimelibTime strtotime(String input, TimelibErrorContainer[] errorsOut, ZoneinfoDb tzdb) {
        input = asBytes(input);
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
        // Padded with NULs like timelib's YYMAXFILL so the DFA scanners never read past the end;
        // lim still stops scanning after the first NUL.
        in.str = trimmed + PADDING;
        in.lim = trimmed.length() + 1;
        in.cur = 0;
        in.time = newUnsetTime();
        in.tzdb = tzdb;
        in.matchers = new Matcher[RULES.length]; // created on first use by scan()

        int t;
        do {
            t = scan(in);
        } while (t != EOI);

        /* do funky checking whether the parsed time was valid time */
        if (in.time.haveTime != 0 && Dow.validTime(in.time.h, in.time.i, in.time.s) == 0) {
            addWarning(in, TIMELIB_WARN_INVALID_TIME, "The parsed time was invalid");
        }
        /* do funky checking whether the parsed date was valid date */
        if (in.time.haveDate != 0 && Dow.validDate(in.time.y, in.time.m, in.time.d) == 0) {
            addWarning(in, TIMELIB_WARN_INVALID_DATE, "The parsed date was invalid");
        }

        if (errorsOut != null) {
            errorsOut[0] = in.errors;
        }
        return in.time;
    }

    // ------------------------------------------------------------------ parse_from_format (Phase D)

    private static void addPbfError(TimelibErrorContainer errors, int errorCode, String message, String str, int pos) {
        if (errors != null) {
            char ch = (pos >= 0 && pos < str.length()) ? str.charAt(pos) : '\0';
            errors.addError(errorCode, pos, ch, message);
        }
    }

    private static void addPbfWarning(TimelibErrorContainer errors, int errorCode, String message, String str, int pos) {
        if (errors != null) {
            char ch = (pos >= 0 && pos < str.length()) ? str.charAt(pos) : '\0';
            errors.addWarning(errorCode, pos, ch, message);
        }
    }

    public static int lookupFormat(char input) {
        return switch (input) {
            case '+' -> TIMELIB_FORMAT_ALLOW_EXTRA_CHARACTERS;
            case '#' -> TIMELIB_FORMAT_ANY_SEPARATOR;
            case 'j' -> TIMELIB_FORMAT_DAY_TWO_DIGIT;
            case 'd' -> TIMELIB_FORMAT_DAY_TWO_DIGIT_PADDED;
            case 'z' -> TIMELIB_FORMAT_DAY_OF_YEAR;
            case 'S' -> TIMELIB_FORMAT_DAY_SUFFIX;
            case 'U' -> TIMELIB_FORMAT_EPOCH_SECONDS;
            case '\\' -> TIMELIB_FORMAT_ESCAPE;
            case 'h' -> TIMELIB_FORMAT_HOUR_TWO_DIGIT_12_MAX;
            case 'g' -> TIMELIB_FORMAT_HOUR_TWO_DIGIT_12_MAX_PADDED;
            case 'H' -> TIMELIB_FORMAT_HOUR_TWO_DIGIT_24_MAX;
            case 'G' -> TIMELIB_FORMAT_HOUR_TWO_DIGIT_24_MAX_PADDED;
            case 'a', 'A' -> TIMELIB_FORMAT_MERIDIAN;
            case 'u' -> TIMELIB_FORMAT_MICROSECOND_SIX_DIGIT;
            case 'v' -> TIMELIB_FORMAT_MILLISECOND_THREE_DIGIT;
            case 'i' -> TIMELIB_FORMAT_MINUTE_TWO_DIGIT;
            case 'n' -> TIMELIB_FORMAT_MONTH_TWO_DIGIT;
            case 'm' -> TIMELIB_FORMAT_MONTH_TWO_DIGIT_PADDED;
            case '?' -> TIMELIB_FORMAT_RANDOM_CHAR;
            case '!' -> TIMELIB_FORMAT_RESET_ALL;
            case '|' -> TIMELIB_FORMAT_RESET_ALL_WHEN_NOT_SET;
            case 's' -> TIMELIB_FORMAT_SECOND_TWO_DIGIT;
            case ';', ':', '/', '.', ',', '-', '(', ')' -> TIMELIB_FORMAT_SEPARATOR;
            case '*' -> TIMELIB_FORMAT_SKIP_TO_SEPARATOR;
            case 'D' -> TIMELIB_FORMAT_TEXTUAL_DAY_3_LETTER;
            case 'l' -> TIMELIB_FORMAT_TEXTUAL_DAY_FULL;
            case 'M' -> TIMELIB_FORMAT_TEXTUAL_MONTH_3_LETTER;
            case 'F' -> TIMELIB_FORMAT_TEXTUAL_MONTH_FULL;
            case 'e', 'P', 'p', 'T', 'O' -> TIMELIB_FORMAT_TIMEZONE_OFFSET;
            case ' ' -> TIMELIB_FORMAT_WHITESPACE;
            case 'y' -> TIMELIB_FORMAT_YEAR_TWO_DIGIT;
            case 'Y' -> TIMELIB_FORMAT_YEAR_FOUR_DIGIT;
            case 'x', 'X' -> TIMELIB_FORMAT_YEAR_EXPANDED;
            default -> TIMELIB_FORMAT_LITERAL;
        };
    }

    public static void resetFields(TimelibTime time) {
        time.y = 1970;
        time.m = 1;
        time.d = 1;
        time.h = 0;
        time.i = 0;
        time.s = 0;
        time.us = 0;
        time.tzInfo = null;
    }

    public static void resetUnsetFields(TimelibTime time) {
        if (time.y == TIMELIB_UNSET) time.y = 1970;
        if (time.m == TIMELIB_UNSET) time.m = 1;
        if (time.d == TIMELIB_UNSET) time.d = 1;
        if (time.h == TIMELIB_UNSET) time.h = 0;
        if (time.i == TIMELIB_UNSET) time.i = 0;
        if (time.s == TIMELIB_UNSET) time.s = 0;
        if (time.us == TIMELIB_UNSET) time.us = 0;
    }

    public static long timelibGetSignedNr(Cursor ptr, int maxLength, TimelibErrorContainer errors, String string) {
        while (ptr.ch() != '\0' && (ptr.ch() < '0' || ptr.ch() > '9') && ptr.ch() != '+' && ptr.ch() != '-') {
            ptr.pos++;
        }
        if (ptr.ch() == '\0') {
            addPbfError(errors, TIMELIB_ERR_UNEXPECTED_DATA, "Found unexpected data", string, 0);
            return 0;
        }
        int sign = 1;
        while (ptr.ch() == '+' || ptr.ch() == '-') {
            if (ptr.ch() == '-') {
                sign = -sign;
            }
            ptr.pos++;
        }
        while (ptr.ch() != '\0' && (ptr.ch() < '0' || ptr.ch() > '9')) {
            ptr.pos++;
        }
        if (ptr.ch() == '\0') {
            addPbfError(errors, TIMELIB_ERR_UNEXPECTED_DATA, "Found unexpected data", string, 0);
            return 0;
        }
        int begin = ptr.pos;
        int len = 0;
        while (ptr.ch() >= '0' && ptr.ch() <= '9' && len < maxLength) {
            ptr.pos++;
            len++;
        }
        String numStr = ptr.s.substring(begin, ptr.pos);
        try {
            java.math.BigInteger bi = new java.math.BigInteger((sign < 0 ? "-" : "") + numStr);
            if (bi.compareTo(java.math.BigInteger.valueOf(Long.MAX_VALUE)) > 0 ||
                    bi.compareTo(java.math.BigInteger.valueOf(Long.MIN_VALUE)) < 0) {
                addPbfError(errors, TIMELIB_ERR_NUMBER_OUT_OF_RANGE, "Number out of range", string, 0);
                return 0;
            }
            return bi.longValue();
        } catch (Exception e) {
            addPbfError(errors, TIMELIB_ERR_NUMBER_OUT_OF_RANGE, "Number out of range", string, 0);
            return 0;
        }
    }

    public static long timelibMeridianWithCheck(Cursor ptr, long h) {
        long retval = 0;
        while (ptr.ch() != '\0' && "AaPp".indexOf(ptr.ch()) < 0) {
            ptr.pos++;
        }
        if (ptr.ch() == '\0') {
            return TIMELIB_UNSET;
        }
        char c = ptr.ch();
        if (c == 'a' || c == 'A') {
            if (h == 12) {
                retval = -12;
            }
        } else if (h != 12) {
            retval = 12;
        }
        ptr.pos++;
        if (ptr.ch() == '.') {
            ptr.pos++;
            if (ptr.ch() != 'm' && ptr.ch() != 'M') {
                return TIMELIB_UNSET;
            }
            ptr.pos++;
            if (ptr.ch() != '.') {
                return TIMELIB_UNSET;
            }
            ptr.pos++;
        } else if (ptr.ch() == 'm' || ptr.ch() == 'M') {
            ptr.pos++;
        } else {
            return TIMELIB_UNSET;
        }
        return retval;
    }

    public static void timelibEatUntilSeparator(Cursor ptr) {
        if (ptr.pos < ptr.s.length()) {
            ptr.pos++;
        }
        while (ptr.pos < ptr.s.length()) {
            char c = ptr.ch();
            if (c == ' ' || c == '\t' || c == '.' || c == ',' || c == ':' || c == ';' ||
                    c == '/' || c == '-' || (c >= '0' && c <= '9')) {
                break;
            }
            ptr.pos++;
        }
    }

    public static int timelibParseTzMinutes(Cursor ptr, TimelibTime t) {
        int retval = TIMELIB_UNSET;
        if (ptr.ch() != '+' && ptr.ch() != '-') {
            return retval;
        }
        char sign = ptr.ch();
        ptr.pos++;
        int start = ptr.pos;
        while (Character.isDigit(ptr.ch())) {
            ptr.pos++;
        }
        if (ptr.pos == start) {
            return retval;
        }
        try {
            long minutes = Long.parseLong(ptr.s.substring(start, ptr.pos));
            t.isLocaltime = 1;
            t.zoneType = TIMELIB_ZONETYPE_OFFSET;
            t.dst = 0;
            retval = (int) (minutes * 60);
            if (sign == '-') {
                retval = -retval;
            }
        } catch (NumberFormatException e) {
            return TIMELIB_UNSET;
        }
        return retval;
    }

    /** C: {@code timelib_parse_from_format} */
    @TruffleBoundary
    public static TimelibTime parseFromFormat(String format, String string, TimelibErrorContainer[] errors, ZoneinfoDb tzdb) {
        if (format == null) format = "";
        if (string == null) string = "";

        TimelibErrorContainer err = new TimelibErrorContainer();
        if (errors != null && errors.length > 0) {
            errors[0] = err;
        }

        TimelibTime time = new TimelibTime();
        time.y = TIMELIB_UNSET;
        time.d = TIMELIB_UNSET;
        time.m = TIMELIB_UNSET;
        time.h = TIMELIB_UNSET;
        time.i = TIMELIB_UNSET;
        time.s = TIMELIB_UNSET;
        time.us = TIMELIB_UNSET;
        time.z = TIMELIB_UNSET;
        time.dst = TIMELIB_UNSET;
        time.isLocaltime = 0;
        time.zoneType = 0;
        time.relative.days = TIMELIB_UNSET;

        Cursor ptr = new Cursor(string);
        int fidx = 0;
        boolean allowExtra = false;
        long isoYear = TIMELIB_UNSET;
        long isoWeekOfYear = TIMELIB_UNSET;
        long isoDayOfWeek = TIMELIB_UNSET;

        while (fidx < format.length() && ptr.pos < string.length()) {
            int begin = ptr.pos;
            char fchar = format.charAt(fidx);
            int spec = lookupFormat(fchar);

            switch (spec) {
                case TIMELIB_FORMAT_TEXTUAL_DAY_3_LETTER, TIMELIB_FORMAT_TEXTUAL_DAY_FULL -> {
                    RelunitEntry relunit = timelibLookupRelunit(ptr);
                    if (relunit == null) {
                        addPbfError(err, TIMELIB_ERR_NO_TEXTUAL_DAY, "A textual day could not be found", string, begin);
                    } else {
                        time.haveRelative = 1;
                        time.relative.haveWeekdayRelative = 1;
                        time.relative.weekday = relunit.multiplier;
                        time.relative.weekdayBehavior = 1;
                    }
                }
                case TIMELIB_FORMAT_DAY_TWO_DIGIT, TIMELIB_FORMAT_DAY_TWO_DIGIT_PADDED -> {
                    if (ptr.ch() < '0' || ptr.ch() > '9') {
                        addPbfError(err, TIMELIB_ERR_UNEXPECTED_DATA, "Unexpected data found.", string, begin);
                    }
                    long val = timelibGetNr(ptr, 2);
                    if (val == TIMELIB_UNSET) {
                        addPbfError(err, TIMELIB_ERR_NO_TWO_DIGIT_DAY, "A two digit day could not be found", string, begin);
                    } else {
                        time.d = val;
                        time.haveDate = 1;
                    }
                }
                case TIMELIB_FORMAT_DAY_SUFFIX -> timelibSkipDaySuffix(ptr);
                case TIMELIB_FORMAT_DAY_OF_YEAR -> {
                    if (ptr.ch() < '0' || ptr.ch() > '9') {
                        addPbfError(err, TIMELIB_ERR_UNEXPECTED_DATA, "Unexpected data found.", string, begin);
                    }
                    if (time.y == TIMELIB_UNSET) {
                        addPbfError(err, TIMELIB_ERR_MERIDIAN_BEFORE_HOUR, "A 'day of year' can only come after a year has been found", string, begin);
                    }
                    long tmp = timelibGetNr(ptr, 3);
                    if (tmp == TIMELIB_UNSET) {
                        addPbfError(err, TIMELIB_ERR_NO_THREE_DIGIT_DAY_OF_YEAR, "A three digit day-of-year could not be found", string, begin);
                    } else if (time.y != TIMELIB_UNSET) {
                        time.haveDate = 1;
                        time.m = 1;
                        time.d = tmp + 1;
                        Tm2Unixtime.doNormalize(time);
                    }
                }
                case TIMELIB_FORMAT_MONTH_TWO_DIGIT, TIMELIB_FORMAT_MONTH_TWO_DIGIT_PADDED -> {
                    if (ptr.ch() < '0' || ptr.ch() > '9') {
                        addPbfError(err, TIMELIB_ERR_UNEXPECTED_DATA, "Unexpected data found.", string, begin);
                    }
                    long val = timelibGetNr(ptr, 2);
                    if (val == TIMELIB_UNSET) {
                        addPbfError(err, TIMELIB_ERR_NO_TWO_DIGIT_MONTH, "A two digit month could not be found", string, begin);
                    } else {
                        time.m = val;
                        time.haveDate = 1;
                    }
                }
                case TIMELIB_FORMAT_TEXTUAL_MONTH_3_LETTER, TIMELIB_FORMAT_TEXTUAL_MONTH_FULL -> {
                    long tmp = timelibLookupMonth(ptr);
                    if (tmp == 0) {
                        addPbfError(err, TIMELIB_ERR_NO_TEXTUAL_MONTH, "A textual month could not be found", string, begin);
                    } else {
                        time.haveDate = 1;
                        time.m = tmp;
                    }
                }
                case TIMELIB_FORMAT_YEAR_TWO_DIGIT -> {
                    if (ptr.ch() < '0' || ptr.ch() > '9') {
                        addPbfError(err, TIMELIB_ERR_UNEXPECTED_DATA, "Unexpected data found.", string, begin);
                    }
                    int[] length = new int[1];
                    long val = timelibGetNrEx(ptr, 2, length);
                    if (val == TIMELIB_UNSET) {
                        addPbfError(err, TIMELIB_ERR_NO_TWO_DIGIT_YEAR, "A two digit year could not be found", string, begin);
                    } else {
                        time.haveDate = 1;
                        time.y = timelibProcessYear(val, length[0]);
                    }
                }
                case TIMELIB_FORMAT_YEAR_FOUR_DIGIT -> {
                    if (ptr.ch() < '0' || ptr.ch() > '9') {
                        addPbfError(err, TIMELIB_ERR_UNEXPECTED_DATA, "Unexpected data found.", string, begin);
                    }
                    long val = timelibGetNr(ptr, 4);
                    if (val == TIMELIB_UNSET) {
                        addPbfError(err, TIMELIB_ERR_NO_FOUR_DIGIT_YEAR, "A four digit year could not be found", string, begin);
                    } else {
                        time.haveDate = 1;
                        time.y = val;
                    }
                }
                case TIMELIB_FORMAT_YEAR_EXPANDED -> {
                    if (ptr.ch() != '+' && ptr.ch() != '-' && (ptr.ch() < '0' || ptr.ch() > '9')) {
                        addPbfError(err, TIMELIB_ERR_UNEXPECTED_DATA, "Unexpected data found.", string, begin);
                    }
                    long val = timelibGetSignedNr(ptr, 19, err, string);
                    if (val == TIMELIB_UNSET) {
                        addPbfError(err, TIMELIB_ERR_NO_FOUR_DIGIT_YEAR, "An expanded digit year could not be found", string, begin);
                    } else {
                        time.haveDate = 1;
                        time.y = val;
                    }
                }
                case TIMELIB_FORMAT_HOUR_TWO_DIGIT_12_MAX, TIMELIB_FORMAT_HOUR_TWO_DIGIT_12_MAX_PADDED -> {
                    if (ptr.ch() < '0' || ptr.ch() > '9') {
                        addPbfError(err, TIMELIB_ERR_UNEXPECTED_DATA, "Unexpected data found.", string, begin);
                    }
                    long val = timelibGetNr(ptr, 2);
                    if (val == TIMELIB_UNSET) {
                        addPbfError(err, TIMELIB_ERR_NO_TWO_DIGIT_HOUR, "A two digit hour could not be found", string, begin);
                    } else if (val > 12) {
                        time.h = val;
                        addPbfError(err, TIMELIB_ERR_HOUR_LARGER_THAN_12, "Hour cannot be higher than 12", string, begin);
                    } else {
                        time.h = val;
                        time.haveTime = 1;
                    }
                }
                case TIMELIB_FORMAT_HOUR_TWO_DIGIT_24_MAX_PADDED, TIMELIB_FORMAT_HOUR_TWO_DIGIT_24_MAX -> {
                    if (ptr.ch() < '0' || ptr.ch() > '9') {
                        addPbfError(err, TIMELIB_ERR_UNEXPECTED_DATA, "Unexpected data found.", string, begin);
                    }
                    long val = timelibGetNr(ptr, 2);
                    if (val == TIMELIB_UNSET) {
                        addPbfError(err, TIMELIB_ERR_NO_TWO_DIGIT_HOUR, "A two digit hour could not be found", string, begin);
                    } else {
                        time.h = val;
                        time.haveTime = 1;
                    }
                }
                case TIMELIB_FORMAT_MERIDIAN -> {
                    if (time.h == TIMELIB_UNSET) {
                        addPbfError(err, TIMELIB_ERR_MERIDIAN_BEFORE_HOUR, "Meridian can only come after an hour has been found", string, begin);
                    }
                    long tmp = timelibMeridianWithCheck(ptr, time.h);
                    if (tmp == TIMELIB_UNSET) {
                        addPbfError(err, TIMELIB_ERR_NO_MERIDIAN, "A meridian could not be found", string, begin);
                    } else {
                        time.haveTime = 1;
                        if (time.h != TIMELIB_UNSET) {
                            time.h += tmp;
                        }
                    }
                }
                case TIMELIB_FORMAT_MINUTE_TWO_DIGIT -> {
                    if (ptr.ch() < '0' || ptr.ch() > '9') {
                        addPbfError(err, TIMELIB_ERR_UNEXPECTED_DATA, "Unexpected data found.", string, begin);
                    }
                    int[] length = new int[1];
                    long min = timelibGetNrEx(ptr, 2, length);
                    if (min == TIMELIB_UNSET || length[0] != 2) {
                        addPbfError(err, TIMELIB_ERR_NO_TWO_DIGIT_MINUTE, "A two digit minute could not be found", string, begin);
                    } else {
                        time.haveTime = 1;
                        time.i = min;
                    }
                }
                case TIMELIB_FORMAT_SECOND_TWO_DIGIT -> {
                    if (ptr.ch() < '0' || ptr.ch() > '9') {
                        addPbfError(err, TIMELIB_ERR_UNEXPECTED_DATA, "Unexpected data found.", string, begin);
                    }
                    int[] length = new int[1];
                    long sec = timelibGetNrEx(ptr, 2, length);
                    if (sec == TIMELIB_UNSET || length[0] != 2) {
                        addPbfError(err, TIMELIB_ERR_NO_TWO_DIGIT_SECOND, "A two digit second could not be found", string, begin);
                    } else {
                        time.haveTime = 1;
                        time.s = sec;
                    }
                }
                case TIMELIB_FORMAT_MICROSECOND_SIX_DIGIT -> {
                    if (ptr.ch() < '0' || ptr.ch() > '9') {
                        addPbfError(err, TIMELIB_ERR_UNEXPECTED_DATA, "Unexpected data found.", string, begin);
                    }
                    int tpos = ptr.pos;
                    long f = timelibGetNr(ptr, 6);
                    int advanced = ptr.pos - tpos;
                    if (f == TIMELIB_UNSET || advanced < 1) {
                        addPbfError(err, TIMELIB_ERR_NO_SIX_DIGIT_MICROSECOND, "A six digit microsecond could not be found", string, begin);
                    } else {
                        time.us = (long) (f * Math.pow(10, 6 - advanced));
                    }
                }
                case TIMELIB_FORMAT_MILLISECOND_THREE_DIGIT -> {
                    if (ptr.ch() < '0' || ptr.ch() > '9') {
                        addPbfError(err, TIMELIB_ERR_UNEXPECTED_DATA, "Unexpected data found.", string, begin);
                    }
                    int tpos = ptr.pos;
                    long f = timelibGetNr(ptr, 3);
                    int advanced = ptr.pos - tpos;
                    if (f == TIMELIB_UNSET || advanced < 1) {
                        addPbfError(err, TIMELIB_ERR_NO_THREE_DIGIT_MILLISECOND, "A three digit millisecond could not be found", string, begin);
                    } else {
                        time.us = (long) (f * Math.pow(10, 3 - advanced) * 1000);
                    }
                }
                case TIMELIB_FORMAT_WHITESPACE -> timelibEatSpaces(ptr);
                case TIMELIB_FORMAT_EPOCH_SECONDS -> {
                    if (ptr.ch() != '+' && ptr.ch() != '-' && (ptr.ch() < '0' || ptr.ch() > '9')) {
                        addPbfError(err, TIMELIB_ERR_UNEXPECTED_DATA, "Unexpected data found.", string, begin);
                    }
                    long tmp = timelibGetSignedNr(ptr, 24, err, string);
                    time.haveZone = 1;
                    time.sse = tmp;
                    time.isLocaltime = 1;
                    time.zoneType = TIMELIB_ZONETYPE_OFFSET;
                    time.z = 0;
                    time.dst = 0;
                    Unixtime2Tm.updateFromSse(time);
                }
                case TIMELIB_FORMAT_ANY_SEPARATOR -> {
                    if (lookupFormat(ptr.ch()) != TIMELIB_FORMAT_SEPARATOR) {
                        addPbfError(err, TIMELIB_ERR_NO_SEP_SYMBOL, "The separation symbol ([;:/.,-]) could not be found", string, begin);
                    } else {
                        ptr.pos++;
                    }
                }
                case TIMELIB_FORMAT_SEPARATOR -> {
                    if (ptr.ch() != fchar) {
                        addPbfError(err, TIMELIB_ERR_NO_SEP_SYMBOL, "The separation symbol could not be found", string, begin);
                    } else {
                        ptr.pos++;
                    }
                }
                case TIMELIB_FORMAT_RESET_ALL -> resetFields(time);
                case TIMELIB_FORMAT_RESET_ALL_WHEN_NOT_SET -> resetUnsetFields(time);
                case TIMELIB_FORMAT_RANDOM_CHAR -> ptr.pos++;
                case TIMELIB_FORMAT_ESCAPE -> {
                    if (fidx + 1 >= format.length()) {
                        addPbfError(err, TIMELIB_ERR_EXPECTED_ESCAPE_CHAR, "Escaped character expected", string, begin);
                    } else {
                        fidx++;
                        char escaped = format.charAt(fidx);
                        if (ptr.ch() != escaped) {
                            addPbfError(err, TIMELIB_ERR_NO_ESCAPED_CHAR, "The escaped character could not be found", string, begin);
                        } else {
                            ptr.pos++;
                        }
                    }
                }
                case TIMELIB_FORMAT_SKIP_TO_SEPARATOR -> timelibEatUntilSeparator(ptr);
                case TIMELIB_FORMAT_ALLOW_EXTRA_CHARACTERS -> allowExtra = true;
                case TIMELIB_FORMAT_YEAR_ISO -> {
                    long val = timelibGetNr(ptr, 4);
                    if (val == TIMELIB_UNSET) {
                        addPbfError(err, TIMELIB_ERR_NO_FOUR_DIGIT_YEAR_ISO, "A four digit ISO year could not be found", string, begin);
                    } else {
                        isoYear = val;
                        time.haveDate = 1;
                    }
                }
                case TIMELIB_FORMAT_WEEK_OF_YEAR_ISO -> {
                    long val = timelibGetNr(ptr, 2);
                    if (val == TIMELIB_UNSET) {
                        addPbfError(err, TIMELIB_ERR_NO_TWO_DIGIT_WEEK, "A two digit ISO week could not be found", string, begin);
                    } else if (val < 1 || val > 53) {
                        isoWeekOfYear = val;
                        addPbfError(err, TIMELIB_ERR_INVALID_WEEK, "ISO Week must be between 1 and 53", string, begin);
                    } else {
                        isoWeekOfYear = val;
                        time.haveDate = 1;
                    }
                }
                case TIMELIB_FORMAT_DAY_OF_WEEK_ISO -> {
                    long val = timelibGetNr(ptr, 1);
                    if (val == TIMELIB_UNSET) {
                        addPbfError(err, TIMELIB_ERR_NO_DAY_OF_WEEK, "A single digit day of week could not be found", string, begin);
                    } else if (val < 1 || val > 7) {
                        isoDayOfWeek = val;
                        addPbfError(err, TIMELIB_ERR_INVALID_DAY_OF_WEEK, "Day of week must be between 1 and 7", string, begin);
                    } else {
                        isoDayOfWeek = val;
                        time.haveDate = 1;
                    }
                }
                case TIMELIB_FORMAT_TIMEZONE_OFFSET -> {
                    int[] tzNotFound = new int[1];
                    int[] dst = new int[1];
                    time.z = timelibParseZone(ptr, dst, time, tzNotFound, tzdb);
                    time.dst = dst[0];
                    if (tzNotFound[0] != 0) {
                        addPbfError(err, TIMELIB_ERR_TZID_NOT_FOUND, "The timezone could not be found in the database", string, begin);
                    } else {
                        time.haveZone = 1;
                    }
                }
                case TIMELIB_FORMAT_TIMEZONE_OFFSET_MINUTES -> {
                    int z = timelibParseTzMinutes(ptr, time);
                    if (z == TIMELIB_UNSET) {
                        addPbfError(err, TIMELIB_ERR_INVALID_TZ_OFFSET, "Invalid timezone offset in minutes", string, begin);
                    } else {
                        time.z = z;
                        time.haveZone = 1;
                    }
                }
                case TIMELIB_FORMAT_LITERAL -> {
                    if (fchar != ptr.ch()) {
                        addPbfError(err, TIMELIB_ERR_WRONG_FORMAT_SEP, "The format separator does not match", string, begin);
                    }
                    ptr.pos++;
                }
                default -> {
                    if (fchar != ptr.ch()) {
                        addPbfError(err, TIMELIB_ERR_WRONG_FORMAT_SEP, "The format separator does not match", string, begin);
                    }
                    ptr.pos++;
                }
            }
            fidx++;
        }

        if (ptr.pos < string.length()) {
            if (allowExtra) {
                addPbfWarning(err, TIMELIB_WARN_TRAILING_DATA, "Trailing data", string, ptr.pos);
            } else {
                addPbfError(err, TIMELIB_ERR_TRAILING_DATA, "Trailing data", string, ptr.pos);
            }
        }

        if (fidx < format.length()) {
            boolean done = false;
            while (fidx < format.length() && !done) {
                char ch = format.charAt(fidx);
                switch (lookupFormat(ch)) {
                    case TIMELIB_FORMAT_RESET_ALL -> resetFields(time);
                    case TIMELIB_FORMAT_RESET_ALL_WHEN_NOT_SET -> resetUnsetFields(time);
                    case TIMELIB_FORMAT_ALLOW_EXTRA_CHARACTERS -> {}
                    default -> {
                        addPbfError(err, TIMELIB_ERR_DATA_MISSING, "Not enough data available to satisfy format", string, ptr.pos);
                        done = true;
                    }
                }
                fidx++;
            }
        }

        // clean up a bit
        if (time.h != TIMELIB_UNSET || time.i != TIMELIB_UNSET || time.s != TIMELIB_UNSET || time.us != TIMELIB_UNSET) {
            if (time.h == TIMELIB_UNSET) time.h = 0;
            if (time.i == TIMELIB_UNSET) time.i = 0;
            if (time.s == TIMELIB_UNSET) time.s = 0;
            if (time.us == TIMELIB_UNSET) time.us = 0;
        }

        // Check for mixing of ISO dates with natural dates
        if (time.y != TIMELIB_UNSET && (isoWeekOfYear != TIMELIB_UNSET || isoYear != TIMELIB_UNSET || isoDayOfWeek != TIMELIB_UNSET)) {
            addPbfError(err, TIMELIB_ERR_MIX_ISO_WITH_NATURAL, "Mixing of ISO dates with natural dates is not allowed", string, ptr.pos);
        }
        if (isoYear != TIMELIB_UNSET && (time.y != TIMELIB_UNSET || time.m != TIMELIB_UNSET || time.d != TIMELIB_UNSET)) {
            addPbfError(err, TIMELIB_ERR_MIX_ISO_WITH_NATURAL, "Mixing of ISO dates with natural dates is not allowed", string, ptr.pos);
        }

        // Convert ISO values
        if (isoYear != TIMELIB_UNSET) {
            if (isoWeekOfYear == TIMELIB_UNSET) {
                isoWeekOfYear = 1;
            }
            if (isoDayOfWeek == TIMELIB_UNSET) {
                isoDayOfWeek = 1;
            }
            long[] y = new long[1];
            long[] m = new long[1];
            long[] d = new long[1];
            Dow.dateFromIsodate(isoYear, isoWeekOfYear, isoDayOfWeek, y, m, d);
            time.y = y[0];
            time.m = m[0];
            time.d = d[0];
        } else if (isoWeekOfYear != TIMELIB_UNSET || isoDayOfWeek != TIMELIB_UNSET) {
            addPbfWarning(err, TIMELIB_WARN_INVALID_DATE, "The parsed date was invalid", string, ptr.pos);
        }

        // valid time check
        if (time.h != TIMELIB_UNSET && time.i != TIMELIB_UNSET && time.s != TIMELIB_UNSET &&
                Dow.validTime(time.h, time.i, time.s) == 0) {
            addPbfWarning(err, TIMELIB_WARN_INVALID_TIME, "The parsed time was invalid", string, ptr.pos);
        }

        // valid date check
        if (time.y != TIMELIB_UNSET && time.m != TIMELIB_UNSET && time.d != TIMELIB_UNSET &&
                Dow.validDate(time.y, time.m, time.d) == 0) {
            addPbfWarning(err, TIMELIB_WARN_INVALID_DATE, "The parsed date was invalid", string, ptr.pos);
        }

        return time;
    }
}
