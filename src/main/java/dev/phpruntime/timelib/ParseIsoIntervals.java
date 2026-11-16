// Ported from timelib 2022.17 (ext/date/lib/parse_iso_intervals.re),
// Copyright (c) 2015-2019 Derick Rethans,
// MIT License (see src/main/resources/META-INF/LICENSE-timelib).

package dev.phpruntime.timelib;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scanner skeleton for {@code parse_iso_intervals.re}.
 *
 * <p>Follows the same longest-match-wins re2c semantics as {@link ParseDate}. Full rules and
 * {@code timelib_strtointerval} are ported in Phase E.
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

        // TODO (Phase E): recurrences
        // TODO (Phase E): datetimebasic | datetimeextended
        // TODO (Phase E): period
        // TODO (Phase E): combinedrep

        r.add(new Rule("[.,/]", "[.,/]", (s, cursor) -> {
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
            addError(s, "Unexpected character");
            return LOOP_STD;
        }));
        return r.toArray(new Rule[0]);
    }

    public static void addError(Scanner s, String error) {
        char ch = s.tok < s.str.length() ? s.str.charAt(s.tok) : '\0';
        s.errors.addError(0, s.tok, ch, error);
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
}
