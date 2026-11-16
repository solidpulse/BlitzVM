package dev.phpruntime.builtins;

import dev.phpruntime.PhpContext;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpStrings;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.time.temporal.WeekFields;
import java.util.Base64;
import java.util.Locale;

/** Byte-oriented string helpers: count_chars, sscanf, shell escaping, strftime and gettext. */
final class TextFunctions {

    private TextFunctions() {
    }

    static void register() {
        Builtins.def("count_chars", 1, 2, (ctx, a) -> countChars(PhpOps.toStr(a[0]), a.length > 1 ? PhpOps.toLong(a[1]) : 0));
        Builtins.def("escapeshellarg", 1, 1, (ctx, a) -> escapeShellArg(PhpOps.toStr(a[0])));
        Builtins.def("escapeshellcmd", 1, 1, (ctx, a) -> escapeShellCmd(PhpOps.toStr(a[0])));
        Builtins.defRef("sscanf", 2, -1, 0xfffffffc, TextFunctions::sscanf);
        Builtins.def("strftime", 1, 2, TextFunctions::strftime);
        Builtins.def("mb_decode_mimeheader", 1, 1, (ctx, a) -> MimeHeader.decode(PhpOps.toStr(a[0])));
        Builtins.def("gettext", 1, 1, (ctx, a) -> PhpOps.toStr(a[0]));
        Builtins.def("_", 1, 1, (ctx, a) -> PhpOps.toStr(a[0]));
    }

    // ------------------------------------------------------------------ count_chars

    private static Object countChars(String s, long mode) {
        if (mode < 0 || mode > 4) {
            throw new PhpError(PhpError.VALUE_ERROR,
                    "count_chars(): Argument #2 ($mode) must be between 0 and 4 (inclusive)", null);
        }
        int[] counts = new int[256];
        for (int i = 0; i < s.length(); i++) {
            counts[s.charAt(i) & 0xff]++;
        }
        if (mode >= 3) {
            StringBuilder sb = new StringBuilder(256);
            for (int i = 0; i < 256; i++) {
                boolean used = counts[i] > 0;
                if (mode == 3 ? used : !used) {
                    sb.append((char) i);
                }
            }
            return sb.toString();
        }
        PhpArray out = new PhpArray();
        for (int i = 0; i < 256; i++) {
            boolean used = counts[i] > 0;
            if (mode == 0 || (mode == 1 ? used : !used)) {
                out.put((long) i, (long) counts[i]);
            }
        }
        return out;
    }

    // ------------------------------------------------------------- shell escaping

    private static String escapeShellArg(String arg) {
        if (arg.indexOf('\0') >= 0) {
            throw new PhpError(PhpError.VALUE_ERROR,
                    "escapeshellarg(): Argument #1 ($arg) must not contain any null bytes", null);
        }
        StringBuilder sb = new StringBuilder(arg.length() + 2);
        sb.append('\'');
        for (int i = 0; i < arg.length(); ) {
            int width = PhpStrings.utf8SequenceLength(arg, i);
            if (width == 0) {
                i++;
                continue;
            }
            if (width > 1) {
                sb.append(arg, i, i + width);
                i += width;
                continue;
            }
            char c = arg.charAt(i++);
            if (c == '\'') {
                sb.append("'\\''");
            } else {
                sb.append(c);
            }
        }
        sb.append('\'');
        return sb.toString();
    }

    private static String escapeShellCmd(String command) {
        if (command.indexOf('\0') >= 0) {
            throw new PhpError(PhpError.VALUE_ERROR,
                    "escapeshellcmd(): Argument #1 ($command) must not contain any null bytes", null);
        }
        int quotes = 0;
        int singleQuotes = 0;
        for (int i = 0; i < command.length(); i++) {
            if (command.charAt(i) == '"') {
                quotes++;
            } else if (command.charAt(i) == '\'') {
                singleQuotes++;
            }
        }
        final String special = "#&;`|*?~<>^()[]{}$\\\n";
        StringBuilder sb = new StringBuilder(command.length());
        for (int i = 0; i < command.length(); ) {
            int width = PhpStrings.utf8SequenceLength(command, i);
            if (width == 0) {
                i++;
                continue;
            }
            if (width > 1) {
                sb.append(command, i, i + width);
                i += width;
                continue;
            }
            char c = command.charAt(i++);
            if (c == '"') {
                if (quotes % 2 == 1) {
                    sb.append('\\');
                }
                sb.append(c);
            } else if (c == '\'') {
                if (singleQuotes % 2 == 1) {
                    sb.append('\\');
                }
                sb.append(c);
            } else if (special.indexOf(c) >= 0) {
                sb.append('\\').append(c);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ sscanf

    /** C sscanf subset PHP exposes: %d %i %u %x %o %f %e %g %s %c %[...] %n %%, with width and *. */
    private static Object sscanf(PhpContext ctx, Object[] a) {
        String input = PhpOps.toStr(a[0]);
        String format = PhpOps.toStr(a[1]);
        boolean arrayForm = a.length == 2;
        int specifiers = countSpecifiers(format);
        if (!arrayForm) {
            if (specifiers == 0) {
                throw new PhpError(PhpError.VALUE_ERROR, "Variable is not assigned by any conversion specifiers", null);
            }
            if (specifiers != a.length - 2) {
                throw new PhpError(PhpError.VALUE_ERROR,
                        "Different numbers of variable names and field specifiers", null);
            }
        }
        java.util.ArrayList<Object> values = new java.util.ArrayList<>();
        int pos = 0;
        int fi = 0;
        boolean failed = false;

        while (fi < format.length() && !failed) {
            char fc = format.charAt(fi);
            if (Character.isWhitespace(fc)) {
                fi++;
                while (fi < format.length() && Character.isWhitespace(format.charAt(fi))) {
                    fi++;
                }
                while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
                    pos++;
                }
                continue;
            }
            if (fc != '%') {
                if (pos < input.length() && input.charAt(pos) == fc) {
                    pos++;
                    fi++;
                    continue;
                }
                failed = true;
                break;
            }
            fi++;
            if (fi >= format.length()) {
                throw new PhpError(PhpError.VALUE_ERROR, "Bad scan conversion character \"\"", null);
            }
            if (format.charAt(fi) == '%') {
                if (pos < input.length() && input.charAt(pos) == '%') {
                    pos++;
                    fi++;
                    continue;
                }
                failed = true;
                break;
            }
            boolean suppress = false;
            if (format.charAt(fi) == '*') {
                suppress = true;
                fi++;
            }
            int width = -1;
            while (fi < format.length() && Character.isDigit(format.charAt(fi))) {
                width = (width < 0 ? 0 : width) * 10 + (format.charAt(fi) - '0');
                fi++;
            }
            // One length modifier is accepted and ignored; a second one is a bad conversion.
            if (fi < format.length() && "hlL".indexOf(format.charAt(fi)) >= 0) {
                fi++;
            }
            if (fi >= format.length()) {
                throw new PhpError(PhpError.VALUE_ERROR, "Bad scan conversion character \"\"", null);
            }
            char conv = format.charAt(fi++);
            if (conv == 'n') {
                if (!suppress) {
                    values.add((long) pos);
                }
                continue;
            }
            if (conv != 'c' && conv != '[') {
                while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
                    pos++;
                }
            }
            Object value = null;
            boolean ok = true;
            switch (conv) {
                case 'd', 'u' -> {
                    long[] parsed = scanInteger(input, pos, 10, width);
                    if (parsed == null) {
                        ok = false;
                    } else {
                        pos = (int) parsed[1];
                        value = conv == 'u' && parsed[0] < 0 ? Long.toUnsignedString(parsed[0]) : (Object) parsed[0];
                    }
                }
                case 'i' -> {
                    long[] parsed = scanInteger(input, pos, 0, width);
                    if (parsed == null) {
                        ok = false;
                    } else {
                        pos = (int) parsed[1];
                        value = parsed[0];
                    }
                }
                case 'x', 'X' -> {
                    long[] parsed = scanInteger(input, pos, 16, width);
                    if (parsed == null) {
                        ok = false;
                    } else {
                        pos = (int) parsed[1];
                        value = parsed[0];
                    }
                }
                case 'o' -> {
                    long[] parsed = scanInteger(input, pos, 8, width);
                    if (parsed == null) {
                        ok = false;
                    } else {
                        pos = (int) parsed[1];
                        value = parsed[0];
                    }
                }
                case 'f', 'e', 'E', 'g', 'G' -> {
                    int end = scanFloat(input, pos, width);
                    if (end == pos) {
                        ok = false;
                    } else {
                        value = Double.parseDouble(input.substring(pos, end));
                        pos = end;
                    }
                }
                case 's' -> {
                    int end = pos;
                    int max = width < 0 ? input.length() : Math.min(input.length(), pos + width);
                    while (end < max && !Character.isWhitespace(input.charAt(end))) {
                        end++;
                    }
                    if (end == pos) {
                        ok = false;
                    } else {
                        value = input.substring(pos, end);
                        pos = end;
                    }
                }
                case 'c' -> {
                    int n = width < 0 ? 1 : width;
                    int end = Math.min(input.length(), pos + n);
                    if (end == pos) {
                        ok = false;
                    } else {
                        value = input.substring(pos, end);
                        pos = end;
                    }
                }
                case '[' -> {
                    int end = scanSet(input, pos, format, fi - 1, width);
                    if (setEnd(format, fi - 1) < 0) {
                        throw new PhpError(PhpError.VALUE_ERROR, "Bad scan conversion character \"[\"", null);
                    }
                    if (end == pos) {
                        ok = false;
                    } else {
                        value = input.substring(pos, end);
                        pos = end;
                    }
                    fi = setEnd(format, fi - 1);
                }
                default -> throw new PhpError(PhpError.VALUE_ERROR,
                        "Bad scan conversion character \"" + conv + "\"", null);
            }
            if (!ok) {
                failed = true;
                if (arrayForm && values.isEmpty() && pos >= input.length()) {
                    // EOF before the first conversion: the array form returns null, not [null].
                    return PhpNull.INSTANCE;
                }
                if (!suppress) {
                    values.add(PhpNull.INSTANCE);
                }
                break;
            }
            if (!suppress) {
                values.add(value);
            }
        }
        if (failed) {
            // php fills the array with null for every conversion that was never reached.
            while (values.size() < specifiers) {
                values.add(PhpNull.INSTANCE);
            }
        }
        if (arrayForm) {
            PhpArray out = new PhpArray();
            for (Object v : values) {
                out.append(v);
            }
            return out;
        }
        int assigned = 0;
        for (int i = 0; i < values.size() && i < a.length - 2; i++) {
            Object v = values.get(i);
            if (v != PhpNull.INSTANCE) {
                a[2 + i] = v;
                assigned++;
            }
        }
        return assigned == 0 ? (failed && pos >= input.length() ? -1L : 0L) : (long) assigned;
    }

    /** Number of non-suppressed conversion specifiers in a format, for sscanf's argument checks. */
    private static int countSpecifiers(String format) {
        int count = 0;
        int i = 0;
        while (i < format.length()) {
            if (format.charAt(i++) != '%') {
                continue;
            }
            if (i < format.length() && format.charAt(i) == '%') {
                i++;
                continue;
            }
            boolean suppress = i < format.length() && format.charAt(i) == '*';
            if (suppress) {
                i++;
            }
            while (i < format.length() && Character.isDigit(format.charAt(i))) {
                i++;
            }
            if (i < format.length() && "hlL".indexOf(format.charAt(i)) >= 0) {
                i++;
            }
            if (i < format.length() && format.charAt(i) == '[') {
                int end = setEnd(format, i);
                i = end < 0 ? format.length() : end;
            } else {
                i++;
            }
            if (!suppress) {
                count++;
            }
        }
        return count;
    }

    /** Value and end offset of an optional-sign integer in the given base (0 = auto), or null. */
    private static long[] scanInteger(String s, int start, int base, int width) {
        int max = width < 0 ? s.length() : Math.min(s.length(), start + width);
        int i = start;
        boolean negative = false;
        if (i < max && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
            negative = s.charAt(i) == '-';
            i++;
        }
        if (base == 16 && i + 1 < max && s.charAt(i) == '0' && (s.charAt(i + 1) == 'x' || s.charAt(i + 1) == 'X')
                && i + 2 < max && digit(s.charAt(i + 2), 16) >= 0) {
            i += 2;
        } else if (base == 0) {
            if (i + 1 < max && s.charAt(i) == '0' && (s.charAt(i + 1) == 'x' || s.charAt(i + 1) == 'X')
                    && i + 2 < max && digit(s.charAt(i + 2), 16) >= 0) {
                base = 16;
                i += 2;
            } else if (i < max && s.charAt(i) == '0') {
                base = 8;
            } else {
                base = 10;
            }
        }
        int begin = i;
        long value = 0;
        while (i < max) {
            int d = digit(s.charAt(i), base);
            if (d < 0) {
                break;
            }
            value = value * base + d;
            i++;
        }
        if (i == begin) {
            return null;
        }
        return new long[]{negative ? -value : value, i};
    }

    private static int scanFloat(String s, int start, int width) {
        int max = width < 0 ? s.length() : Math.min(s.length(), start + width);
        int i = start;
        if (i < max && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
            i++;
        }
        int digits = 0;
        while (i < max && Character.isDigit(s.charAt(i))) {
            i++;
            digits++;
        }
        if (i < max && s.charAt(i) == '.') {
            i++;
            while (i < max && Character.isDigit(s.charAt(i))) {
                i++;
                digits++;
            }
        }
        if (digits == 0) {
            return start;
        }
        if (i < max && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            int j = i + 1;
            if (j < max && (s.charAt(j) == '+' || s.charAt(j) == '-')) {
                j++;
            }
            int expDigits = 0;
            while (j < max && Character.isDigit(s.charAt(j))) {
                j++;
                expDigits++;
            }
            if (expDigits > 0) {
                i = j;
            }
        }
        return i;
    }

    private static int digit(char c, int base) {
        int v = Character.digit(c, base);
        return v >= 0 ? v : -1;
    }

    /** End index of characters in the %[...] set, or -1 for a malformed set. */
    private static int scanSet(String s, int start, String format, int openBracket, int width) {
        int end = setEnd(format, openBracket);
        if (end < 0) {
            return -1;
        }
        String spec = format.substring(openBracket + 1, end - 1);
        boolean negate = spec.startsWith("^");
        if (negate) {
            spec = spec.substring(1);
        }
        boolean[] inSet = new boolean[256];
        for (int i = 0; i < spec.length(); i++) {
            char c = spec.charAt(i);
            if (i + 2 < spec.length() && spec.charAt(i + 1) == '-') {
                char to = spec.charAt(i + 2);
                for (int b = c; b <= to; b++) {
                    inSet[b & 0xff] = true;
                }
                i += 2;
            } else {
                inSet[c & 0xff] = true;
            }
        }
        int max = width < 0 ? s.length() : Math.min(s.length(), start + width);
        int i = start;
        while (i < max && inSet[s.charAt(i) & 0xff] != negate) {
            i++;
        }
        return i;
    }

    private static int setEnd(String format, int openBracket) {
        int i = openBracket + 1;
        if (i < format.length() && format.charAt(i) == '^') {
            i++;
        }
        if (i < format.length() && format.charAt(i) == ']') {
            i++;
        }
        while (i < format.length() && format.charAt(i) != ']') {
            i++;
        }
        return i < format.length() ? i + 1 : -1;
    }

    // ------------------------------------------------------------------ strftime

    private static Object strftime(PhpContext ctx, Object[] a) {
        String format = PhpOps.toStr(a[0]);
        long timestamp = a.length > 1 && a[1] != PhpNull.INSTANCE
                ? PhpOps.toLong(a[1])
                : System.currentTimeMillis() / 1000;
        ZoneId zone;
        try {
            zone = ZoneId.of(ContextState.of(ctx).timezoneName);
        } catch (RuntimeException e) {
            zone = DateFunctions.UTC;
        }
        ZonedDateTime t = ZonedDateTime.ofInstant(Instant.ofEpochSecond(timestamp), zone);
        return formatStrftime(format, t);
    }

    private static String formatStrftime(String format, ZonedDateTime t) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < format.length()) {
            char c = format.charAt(i++);
            if (c != '%') {
                sb.append(c);
                continue;
            }
            int start = i - 1;
            char pad = 0;
            boolean noPad = false;
            boolean upper = false;
            while (i < format.length() && "-_0^#".indexOf(format.charAt(i)) >= 0) {
                char flag = format.charAt(i++);
                if (flag == '-') {
                    noPad = true;
                } else if (flag == '_') {
                    pad = ' ';
                } else if (flag == '0') {
                    pad = '0';
                } else if (flag == '^') {
                    upper = true;
                }
            }
            while (i < format.length() && Character.isDigit(format.charAt(i))) {
                i++;
            }
            if (i < format.length() && (format.charAt(i) == 'E' || format.charAt(i) == 'O')) {
                i++;
            }
            if (i >= format.length()) {
                sb.append(format, start, i);
                break;
            }
            char conv = format.charAt(i++);
            String value = switch (conv) {
                case 'a' -> DateFunctions.DAY_SHORT_NAMES[t.getDayOfWeek().getValue() % 7];
                case 'A' -> DateFunctions.DAY_FULL_NAMES[t.getDayOfWeek().getValue() % 7];
                case 'b', 'h' -> DateFunctions.MON_SHORT_NAMES[t.getMonthValue() - 1];
                case 'B' -> DateFunctions.MON_FULL_NAMES[t.getMonthValue() - 1];
                case 'c' -> formatStrftime("%a %b %e %H:%M:%S %Y", t);
                case 'C' -> number(t.getYear() / 100, 2, pad, noPad);
                case 'd' -> number(t.getDayOfMonth(), 2, pad, noPad);
                case 'D' -> formatStrftime("%m/%d/%y", t);
                case 'e' -> number(t.getDayOfMonth(), 2, pad == 0 ? ' ' : pad, noPad);
                case 'F' -> formatStrftime("%Y-%m-%d", t);
                case 'g' -> number(Math.floorMod(isoWeekYear(t), 100), 2, pad, noPad);
                case 'G' -> Long.toString(isoWeekYear(t));
                case 'H' -> number(t.getHour(), 2, pad, noPad);
                case 'I' -> number(hour12(t.getHour()), 2, pad, noPad);
                case 'j' -> number(t.getDayOfYear(), 3, pad, noPad);
                case 'k' -> number(t.getHour(), 2, pad == 0 ? ' ' : pad, noPad);
                case 'l' -> number(hour12(t.getHour()), 2, pad == 0 ? ' ' : pad, noPad);
                case 'm' -> number(t.getMonthValue(), 2, pad, noPad);
                case 'M' -> number(t.getMinute(), 2, pad, noPad);
                case 'n' -> "\n";
                case 'p' -> t.getHour() < 12 ? "AM" : "PM";
                case 'P' -> t.getHour() < 12 ? "am" : "pm";
                case 'r' -> formatStrftime("%I:%M:%S %p", t);
                case 'R' -> formatStrftime("%H:%M", t);
                case 's' -> Long.toString(t.toEpochSecond());
                case 'S' -> number(t.getSecond(), 2, pad, noPad);
                case 't' -> "\t";
                case 'T' -> formatStrftime("%H:%M:%S", t);
                case 'u' -> Integer.toString(t.getDayOfWeek().getValue());
                case 'U' -> number(weekNumber(t, true), 2, pad, noPad);
                case 'V' -> number(t.get(WeekFields.ISO.weekOfWeekBasedYear()), 2, pad, noPad);
                case 'w' -> Integer.toString(t.getDayOfWeek().getValue() % 7);
                case 'W' -> number(weekNumber(t, false), 2, pad, noPad);
                case 'x' -> formatStrftime("%m/%d/%y", t);
                case 'X' -> formatStrftime("%H:%M:%S", t);
                case 'y' -> number(Math.floorMod(t.getYear(), 100), 2, pad, noPad);
                case 'Y' -> Integer.toString(t.getYear());
                case 'z' -> zoneOffset(t);
                case 'Z' -> t.format(java.time.format.DateTimeFormatter.ofPattern("z", Locale.ENGLISH));
                case '%' -> "%";
                default -> null;
            };
            if (value == null) {
                sb.append(format, start, i);
            } else {
                sb.append(upper ? PhpStrings.upper(value) : value);
            }
        }
        return sb.toString();
    }

    private static int hour12(int hour) {
        int h = hour % 12;
        return h == 0 ? 12 : h;
    }

    private static int isoWeekYear(ZonedDateTime t) {
        return t.get(WeekFields.ISO.weekBasedYear());
    }

    /** %U: weeks start on Sunday; %W: weeks start on Monday. Both count the first partial week as 0. */
    private static int weekNumber(ZonedDateTime t, boolean sundayFirst) {
        int dayOfYear = t.getDayOfYear() - 1;
        int weekday = t.getDayOfWeek().getValue() % 7;
        if (!sundayFirst) {
            weekday = (weekday + 6) % 7;
        }
        return (dayOfYear + 7 - weekday) / 7;
    }

    private static String number(int value, int width, char pad, boolean noPad) {
        String s = Integer.toString(value);
        if (noPad || s.length() >= width) {
            return s;
        }
        char p = pad == 0 ? '0' : pad;
        return String.valueOf(p).repeat(width - s.length()) + s;
    }

    private static String zoneOffset(ZonedDateTime t) {
        int seconds = t.getOffset().getTotalSeconds();
        char sign = seconds < 0 ? '-' : '+';
        int abs = Math.abs(seconds);
        return String.format(Locale.ROOT, "%c%02d%02d", sign, abs / 3600, (abs % 3600) / 60);
    }

    // ------------------------------------------------------- MIME header decoding

    /** mb_decode_mimeheader: RFC 2047 encoded words, B and Q, converted to UTF-8. */
    private static final class MimeHeader {

        private MimeHeader() {
        }

        static String decode(String input) {
            StringBuilder out = new StringBuilder(input.length());
            StringBuilder pending = new StringBuilder();
            boolean lastEncoded = false;
            int i = 0;
            while (i < input.length()) {
                if (input.startsWith("=?", i)) {
                    int csEnd = input.indexOf('?', i + 2);
                    if (csEnd < 0) {
                        pending.append(input.substring(i));
                        break;
                    }
                    if (csEnd + 2 >= input.length() || input.charAt(csEnd + 2) != '?') {
                        pending.append(input, i, csEnd + 1);
                        i = csEnd + 1;
                        continue;
                    }
                    char enc = input.charAt(csEnd + 1);
                    int textEnd = input.indexOf("?=", csEnd + 3);
                    String charsetName = input.substring(i + 2, csEnd);
                    if (textEnd < 0 || (enc != 'B' && enc != 'b' && enc != 'Q' && enc != 'q')
                            || charset(charsetName) == null) {
                        if (textEnd >= 0) {
                            pending.append(input, i, textEnd + 2);
                            i = textEnd + 2;
                        } else {
                            pending.append(input, i, csEnd + 1);
                            i = csEnd + 1;
                        }
                        continue;
                    }
                    String text = input.substring(csEnd + 3, textEnd);
                    byte[] decoded = enc == 'B' || enc == 'b' ? decodeBase64(text) : decodeQ(text);
                    String converted = convert(decoded, charsetName);
                    // Whitespace between two encoded words is not part of the header value.
                    if (lastEncoded && isWhitespace(pending)) {
                        pending.setLength(0);
                    } else {
                        out.append(flatten(pending.toString()));
                        pending.setLength(0);
                    }
                    out.append(converted);
                    lastEncoded = true;
                    i = textEnd + 2;
                } else {
                    char c = input.charAt(i++);
                    pending.append(c);
                    if (!Character.isWhitespace(c)) {
                        lastEncoded = false;
                    }
                }
            }
            out.append(flatten(pending.toString()));
            return out.toString();
        }

        private static String flatten(String text) {
            return text.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ');
        }

        private static boolean isWhitespace(CharSequence s) {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (!Character.isWhitespace(c)) {
                    return false;
                }
            }
            return true;
        }

        private static Charset charset(String name) {
            MbConvertFunctions.Encoding enc = MbConvertFunctions.encoding(name);
            if (enc != null) {
                if (enc.charset() != null) {
                    return enc.charset();
                }
                return null;
            }
            try {
                return Charset.forName(PhpStrings.toJava(name));
            } catch (RuntimeException e) {
                return null;
            }
        }

        private static String convert(byte[] data, String charsetName) {
            Charset cs = charset(charsetName);
            if (cs == null) {
                return PhpStrings.of(data);
            }
            return PhpStrings.fromJava(new String(data, cs));
        }

        private static byte[] decodeQ(String text) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(text.length());
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '_') {
                    out.write(' ');
                } else if (c == '=') {
                    if (i + 2 < text.length() && hex(text.charAt(i + 1)) >= 0 && hex(text.charAt(i + 2)) >= 0) {
                        out.write(hex(text.charAt(i + 1)) << 4 | hex(text.charAt(i + 2)));
                        i += 2;
                    } else {
                        out.write('=');
                        break;
                    }
                } else {
                    out.write(c & 0xff);
                }
            }
            return out.toByteArray();
        }

        private static byte[] decodeBase64(String text) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(text.length());
            StringBuilder group = new StringBuilder(4);
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '=') {
                    if (group.length() > 0) {
                        while (group.length() < 4) {
                            group.append('=');
                        }
                        try {
                            out.writeBytes(Base64.getDecoder().decode(group.toString()));
                        } catch (IllegalArgumentException ignored) {
                            // php's filter emits nothing for an undecodable group
                        }
                        group.setLength(0);
                    }
                } else if (base64(c) >= 0) {
                    group.append(c);
                    if (group.length() == 4) {
                        out.writeBytes(Base64.getDecoder().decode(group.toString()));
                        group.setLength(0);
                    }
                } else {
                    out.write('?');
                }
            }
            if (group.length() > 0) {
                while (group.length() < 4) {
                    group.append('=');
                }
                try {
                    out.writeBytes(Base64.getDecoder().decode(group.toString()));
                } catch (IllegalArgumentException ignored) {
                }
            }
            return out.toByteArray();
        }

        private static int base64(char c) {
            if (c >= 'A' && c <= 'Z') {
                return c - 'A';
            }
            if (c >= 'a' && c <= 'z') {
                return c - 'a' + 26;
            }
            if (c >= '0' && c <= '9') {
                return c - '0' + 52;
            }
            if (c == '+' || c == '/') {
                return c == '+' ? 62 : 63;
            }
            return -1;
        }

        private static int hex(char c) {
            return Character.digit(c, 16);
        }
    }
}
