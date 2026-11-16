// Ported from timelib 2022.17 (ext/date/lib/parse_iso_intervals.re),
// Copyright (c) 2015-2019 Derick Rethans,
// MIT License (see src/main/resources/META-INF/LICENSE-timelib).

package dev.phpruntime.timelib;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dev.phpruntime.timelib.TimelibConstants.*;

/**
 * Scanner for {@code parse_iso_intervals.re}.
 *
 * <p>Follows the same longest-match-wins re2c semantics as {@link ParseDate}.
 */
public final class ParseIsoIntervals {

    private ParseIsoIntervals() {
    }

    public static final int EOI = 257;
    public static final int TIMELIB_PERIOD = 260;
    public static final int TIMELIB_ISO_DATE = 261;
    public static final int TIMELIB_ERROR = 999;
    public static final int TIMELIB_MAX_ERRORS = 1000;

    private static final int LOOP_STD = -1;

    private static String g(String regex) {
        return "(?:" + regex + ")";
    }

    private static final String ANY = g("[\\u0000-\\uffff]");
    private static final String NUMBER = g("[0-9]+");

    private static final String HOUR24LZ = g("[01][0-9]|2[0-4]");
    private static final String MINUTELZ = g("[0-5][0-9]");
    private static final String MONTHLZ = g("0[1-9]|1[0-2]");
    private static final String MONTHLZZ = g("0[0-9]|1[0-2]");
    private static final String DAYLZ = g("0[1-9]|[1-2][0-9]|3[01]");
    private static final String DAYLZZ = g("0[0-9]|[1-2][0-9]|3[01]");
    private static final String SECONDLZ = MINUTELZ;
    private static final String YEAR4 = g("[0-9]{4}");
    private static final String WEEKOFYEAR = g("0[1-9]|[1-4][0-9]|5[0-3]");

    private static final String SPACE = g("[ \\t]+");
    private static final String DATETIMEBASIC = g(YEAR4 + MONTHLZ + DAYLZ + "T" + HOUR24LZ + MINUTELZ + SECONDLZ + "Z");
    private static final String DATETIMEEXTENDED = g(YEAR4 + "-" + MONTHLZ + "-" + DAYLZ + "T" + HOUR24LZ + ":" + MINUTELZ + ":" + SECONDLZ + "Z");
    private static final String PERIOD = g("P(?:" + NUMBER + "Y)?(?:" + NUMBER + "M)?(?:" + NUMBER + "W)?(?:" + NUMBER + "D)?(?:T(?:" + NUMBER + "H)?(?:" + NUMBER + "M)?(?:" + NUMBER + "S)?)?");
    private static final String COMBINEDREP = g("P" + YEAR4 + "-" + MONTHLZZ + "-" + DAYLZZ + "T" + HOUR24LZ + ":" + MINUTELZ + ":" + SECONDLZ);
    private static final String RECURRENCES = g("R" + NUMBER);

    public static final class Scanner {
        public String str;
        public int lim;
        public int cur;
        public int tok;
        public int pos;
        public int line;
        public int len;
        public TimelibErrorContainer errors;

        public TimelibTime begin;
        public TimelibTime end;
        public TimelibRelTime period;
        public int recurrences;

        public int havePeriod;
        public int haveRecurrences;
        public int haveDate;
        public int haveBeginDate;
        public int haveEndDate;
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

        r.add(new Rule("recurrences", RECURRENCES, (s, cursor) -> {
            s.cur = cursor;
            String str = s.str.substring(s.tok, s.cur);
            ParseDate.Cursor ptr = new ParseDate.Cursor(str);
            ptr.pos++; // skip 'R'
            s.recurrences = (int) timelibGetUnsignedNr(ptr, 9);
            s.haveRecurrences = 1;
            return TIMELIB_PERIOD;
        }));

        r.add(new Rule("datetimebasic | datetimeextended", DATETIMEBASIC + "|" + DATETIMEEXTENDED, (s, cursor) -> {
            s.cur = cursor;
            TimelibTime current;
            if (s.haveDate != 0 || s.havePeriod != 0) {
                current = s.end;
                s.haveEndDate = 1;
            } else {
                current = s.begin;
                s.haveBeginDate = 1;
            }
            String str = s.str.substring(s.tok, s.cur);
            ParseDate.Cursor ptr = new ParseDate.Cursor(str);
            current.y = timelibGetNr(ptr, 4);
            current.m = timelibGetNr(ptr, 2);
            current.d = timelibGetNr(ptr, 2);
            current.h = timelibGetNr(ptr, 2);
            current.i = timelibGetNr(ptr, 2);
            current.s = timelibGetNr(ptr, 2);
            s.haveDate = 1;
            return TIMELIB_ISO_DATE;
        }));

        r.add(new Rule("combinedrep", COMBINEDREP, (s, cursor) -> {
            s.cur = cursor;
            String str = s.str.substring(s.tok, s.cur);
            ParseDate.Cursor ptr = new ParseDate.Cursor(str);
            ptr.pos++; // skip 'P'
            s.period.y = timelibGetUnsignedNr(ptr, 4);
            ptr.pos++;
            s.period.m = timelibGetUnsignedNr(ptr, 2);
            ptr.pos++;
            s.period.d = timelibGetUnsignedNr(ptr, 2);
            ptr.pos++;
            s.period.h = timelibGetUnsignedNr(ptr, 2);
            ptr.pos++;
            s.period.i = timelibGetUnsignedNr(ptr, 2);
            ptr.pos++;
            s.period.s = timelibGetUnsignedNr(ptr, 2);
            s.havePeriod = 1;
            return TIMELIB_PERIOD;
        }));

        r.add(new Rule("period", PERIOD, (s, cursor) -> {
            s.cur = cursor;
            String str = s.str.substring(s.tok, s.cur);
            ParseDate.Cursor ptr = new ParseDate.Cursor(str);
            ptr.pos++; // skip 'P'
            boolean inTime = false;
            do {
                if (ptr.ch() == 'T') {
                    inTime = true;
                    ptr.pos++;
                }
                if (ptr.ch() == '\0') {
                    addError(s, "Missing expected time part");
                    break;
                }
                long nr = timelibGetUnsignedNr(ptr, 12);
                char spec = ptr.ch();
                switch (spec) {
                    case 'Y' -> s.period.y = nr;
                    case 'W' -> s.period.d += nr * 7;
                    case 'D' -> s.period.d += nr;
                    case 'H' -> s.period.h = nr;
                    case 'S' -> s.period.s = nr;
                    case 'M' -> {
                        if (inTime) {
                            s.period.i = nr;
                        } else {
                            s.period.m = nr;
                        }
                    }
                    default -> addError(s, "Undefined period specifier");
                }
                ptr.pos++;
            } while (s.errors.errorCount == 0 && ptr.ch() != '\0');
            s.havePeriod = 1;
            return TIMELIB_PERIOD;
        }));

        r.add(new Rule("delims", "[.,/ \\t]", (s, cursor) -> {
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
            addError(s, "Unexpected character");
            return LOOP_STD;
        }));

        return r.toArray(new Rule[0]);
    }

    public static void addError(Scanner s, String error) {
        if (s.errors == null || s.errors.errorCount >= TIMELIB_MAX_ERRORS) {
            return;
        }
        int pos = s.tok;
        char ch = (s.str != null && pos >= 0 && pos < s.str.length()) ? s.str.charAt(pos) : '\0';
        s.errors.addError(0, pos, ch, error);
    }

    public static long timelibGetNr(ParseDate.Cursor ptr, int maxLength) {
        while (ptr.ch() != '\0' && (ptr.ch() < '0' || ptr.ch() > '9')) {
            ptr.pos++;
        }
        if (ptr.ch() == '\0') {
            return TIMELIB_UNSET;
        }
        int begin = ptr.pos;
        int len = 0;
        while (ptr.ch() >= '0' && ptr.ch() <= '9' && len < maxLength) {
            ptr.pos++;
            len++;
        }
        if (len == 0) {
            return TIMELIB_UNSET;
        }
        try {
            return Long.parseLong(ptr.s.substring(begin, ptr.pos));
        } catch (NumberFormatException e) {
            return TIMELIB_UNSET;
        }
    }

    public static long timelibGetUnsignedNr(ParseDate.Cursor ptr, int maxLength) {
        long dir = 1;
        while (ptr.ch() != '\0' && (ptr.ch() < '0' || ptr.ch() > '9') && ptr.ch() != '+' && ptr.ch() != '-') {
            ptr.pos++;
        }
        if (ptr.ch() == '\0') {
            return TIMELIB_UNSET;
        }
        while (ptr.ch() == '+' || ptr.ch() == '-') {
            if (ptr.ch() == '-') {
                dir *= -1;
            }
            ptr.pos++;
        }
        long val = timelibGetNr(ptr, maxLength);
        if (val == TIMELIB_UNSET) {
            return TIMELIB_UNSET;
        }
        return dir * val;
    }

    static int scan(Scanner s) {
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

    public static final class IsoIntervalResult {
        public TimelibTime begin;
        public TimelibTime end;
        public TimelibRelTime period;
        public int recurrences = 1;
        public TimelibErrorContainer errors;
        public boolean haveBeginDate;
        public boolean haveEndDate;
        public boolean havePeriod;
        public boolean haveRecurrences;
    }

    /** C: {@code timelib_strtointerval} */
    @TruffleBoundary
    public static IsoIntervalResult strToInterval(String s, TimelibErrorContainer[] errorsOut) {
        Scanner in = new Scanner();
        in.errors = new TimelibErrorContainer();
        if (errorsOut != null && errorsOut.length > 0) {
            errorsOut[0] = in.errors;
        }

        if (s == null) {
            s = "";
        }
        String trimmed = s.trim();
        if (trimmed.isEmpty()) {
            addError(in, "Empty string");
            IsoIntervalResult res = new IsoIntervalResult();
            res.errors = in.errors;
            return res;
        }

        in.str = trimmed + "\0";
        in.lim = in.str.length();
        in.cur = 0;

        in.begin = new TimelibTime();
        in.begin.y = TIMELIB_UNSET;
        in.begin.d = TIMELIB_UNSET;
        in.begin.m = TIMELIB_UNSET;
        in.begin.h = TIMELIB_UNSET;
        in.begin.i = TIMELIB_UNSET;
        in.begin.s = TIMELIB_UNSET;
        in.begin.us = 0;
        in.begin.z = 0;
        in.begin.dst = 0;
        in.begin.isLocaltime = 0;
        in.begin.zoneType = TIMELIB_ZONETYPE_OFFSET;

        in.end = new TimelibTime();
        in.end.y = TIMELIB_UNSET;
        in.end.d = TIMELIB_UNSET;
        in.end.m = TIMELIB_UNSET;
        in.end.h = TIMELIB_UNSET;
        in.end.i = TIMELIB_UNSET;
        in.end.s = TIMELIB_UNSET;
        in.end.us = 0;
        in.end.z = 0;
        in.end.dst = 0;
        in.end.isLocaltime = 0;
        in.end.zoneType = TIMELIB_ZONETYPE_OFFSET;

        in.period = new TimelibRelTime();
        in.period.y = 0;
        in.period.d = 0;
        in.period.m = 0;
        in.period.h = 0;
        in.period.i = 0;
        in.period.s = 0;
        in.period.weekday = 0;
        in.period.weekdayBehavior = 0;
        in.period.firstLastDayOf = 0;
        in.period.days = TIMELIB_UNSET;

        in.recurrences = 1;

        in.matchers = new Matcher[RULES.length];
        for (int i = 0; i < RULES.length; i++) {
            in.matchers[i] = RULES[i].pattern.matcher(in.str);
        }

        int t;
        do {
            t = scan(in);
        } while (t != EOI);

        IsoIntervalResult res = new IsoIntervalResult();
        res.errors = in.errors;
        if (in.haveBeginDate != 0) {
            res.begin = in.begin;
            res.haveBeginDate = true;
        }
        if (in.haveEndDate != 0) {
            res.end = in.end;
            res.haveEndDate = true;
        }
        if (in.havePeriod != 0) {
            res.period = in.period;
            res.havePeriod = true;
        }
        if (in.haveRecurrences != 0) {
            res.recurrences = in.recurrences;
            res.haveRecurrences = true;
        }
        return res;
    }
}
