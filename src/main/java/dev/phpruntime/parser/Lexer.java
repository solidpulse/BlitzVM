package dev.phpruntime.parser;

import dev.phpruntime.parser.Token.Kind;
import dev.phpruntime.parser.Token.Part;

import java.util.ArrayList;
import java.util.List;

/**
 * Hand-written PHP lexer. Starts in HTML mode (everything up to {@code <?php} is
 * inline output) unless constructed in code mode, which is used to re-lex
 * expressions embedded in interpolated strings.
 */
public final class Lexer {

    // Longest operators first so that maximal munch works with a simple prefix scan.
    private static final String[] OPS = {
        "<=>", "===", "!==", "**=", "...", "<<=", ">>=", "??=", "?->",
        "==", "!=", "<>", "<=", ">=", "&&", "||", "++", "--", "+=", "-=", "*=", "/=",
        ".=", "%=", "->", "=>", "::", "<<", ">>", "??", "**", "|=", "&=", "^=",
        "+", "-", "*", "/", "%", "=", "<", ">", "!", ".", "(", ")", "[", "]", "{", "}",
        ",", ";", "?", ":", "&", "|", "^", "~", "@"
    };

    private final String src;
    private int pos;
    private int line = 1;
    private boolean inCode;
    private final List<Token> out = new ArrayList<>();

    private Lexer(String src, boolean codeMode) {
        this.src = src;
        this.inCode = codeMode;
    }

    public static List<Token> tokenize(String src) {
        return new Lexer(src, false).run();
    }

    /** Tokenize a fragment that is already PHP code (no open tag needed). */
    public static List<Token> tokenizeCode(String src) {
        return new Lexer(src, true).run();
    }

    private List<Token> run() {
        while (pos < src.length()) {
            if (inCode) {
                lexCode();
            } else {
                lexHtml();
            }
        }
        out.add(new Token(Kind.EOF, "", pos, line));
        return out;
    }

    private void lexHtml() {
        int start = pos;
        int open = src.indexOf("<?", pos);
        int end = open < 0 ? src.length() : open;
        if (end > start) {
            String html = src.substring(start, end);
            out.add(new Token(Kind.INLINE_HTML, html, start, line));
            countLines(html);
        }
        pos = end;
        if (open < 0) {
            return;
        }
        if (src.startsWith("<?php", pos)) {
            pos += 5;
        } else if (src.startsWith("<?=", pos)) {
            out.add(new Token(Kind.OPEN_ECHO, "<?=", pos, line));
            pos += 3;
        } else {
            pos += 2; // short open tag "<?"
        }
        inCode = true;
    }

    private void lexCode() {
        char c = src.charAt(pos);
        if (c == '\n') {
            line++;
            pos++;
            return;
        }
        if (Character.isWhitespace(c)) {
            pos++;
            return;
        }
        if (src.startsWith("?>", pos)) {
            out.add(new Token(Kind.CLOSE_TAG, "?>", pos, line));
            pos += 2;
            // A single newline directly after the close tag is swallowed, as in PHP.
            if (pos < src.length() && src.charAt(pos) == '\n') {
                pos++;
                line++;
            }
            inCode = false;
            return;
        }
        if (src.startsWith("#[", pos)) {
            out.add(new Token(Kind.OP, "#[", pos, line));
            pos += 2;
            return;
        }
        if (c == '#' || src.startsWith("//", pos)) {
            skipLineComment();
            return;
        }
        if (src.startsWith("/*", pos)) {
            int close = src.indexOf("*/", pos + 2);
            int end = close < 0 ? src.length() : close + 2;
            countLines(src.substring(pos, end));
            pos = end;
            return;
        }
        if (c == '$' && pos + 1 < src.length() && isIdentStart(src.charAt(pos + 1))) {
            int start = pos++;
            String name = readIdent();
            out.add(new Token(Kind.VARIABLE, name, start, line));
            return;
        }
        if (isIdentStart(c) || c == '\\') {
            int start = pos;
            String name = readIdent();
            out.add(new Token(Kind.IDENT, name, start, line));
            return;
        }
        if (Character.isDigit(c) || (c == '.' && pos + 1 < src.length() && Character.isDigit(src.charAt(pos + 1)))) {
            lexNumber();
            return;
        }
        if (c == '\'') {
            lexSingleQuoted();
            return;
        }
        if (c == '"') {
            lexDoubleQuoted();
            return;
        }
        if (src.startsWith("<<<", pos)) {
            lexHeredoc();
            return;
        }
        for (String op : OPS) {
            if (src.startsWith(op, pos)) {
                out.add(new Token(Kind.OP, op, pos, line));
                pos += op.length();
                return;
            }
        }
        throw new PhpParseError("Unexpected character '" + c + "'", line);
    }

    private void skipLineComment() {
        // A line comment ends at newline or at "?>", whichever comes first.
        while (pos < src.length() && src.charAt(pos) != '\n' && !src.startsWith("?>", pos)) {
            pos++;
        }
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_' || c >= 0x80;
    }

    private static boolean isIdentPart(char c) {
        return isIdentStart(c) || Character.isDigit(c);
    }

    private String readIdent() {
        int start = pos;
        while (pos < src.length() && (isIdentPart(src.charAt(pos)) || src.charAt(pos) == '\\')) {
            pos++;
        }
        return src.substring(start, pos);
    }

    private void lexNumber() {
        int start = pos;
        if (src.startsWith("0x", pos) || src.startsWith("0X", pos)) {
            pos += 2;
            while (pos < src.length() && (Character.digit(src.charAt(pos), 16) >= 0 || src.charAt(pos) == '_')) {
                pos++;
            }
            String hex = src.substring(start + 2, pos).replace("_", "");
            out.add(new Token(Kind.INT, Long.toString(Long.parseLong(hex, 16)), start, line));
            return;
        }
        boolean isFloat = false;
        while (pos < src.length()) {
            char ch = src.charAt(pos);
            if (Character.isDigit(ch) || ch == '_') {
                pos++;
            } else if (ch == '.' && !isFloat && pos + 1 < src.length() && Character.isDigit(src.charAt(pos + 1))) {
                isFloat = true;
                pos++;
            } else if ((ch == 'e' || ch == 'E') && pos + 1 < src.length()
                    && (Character.isDigit(src.charAt(pos + 1)) || src.charAt(pos + 1) == '-' || src.charAt(pos + 1) == '+')) {
                isFloat = true;
                pos += 2;
            } else {
                break;
            }
        }
        String text = src.substring(start, pos).replace("_", "");
        out.add(new Token(isFloat ? Kind.FLOAT : Kind.INT, text, start, line));
    }

    private void lexSingleQuoted() {
        int start = pos++;
        int startLine = line;
        StringBuilder sb = new StringBuilder();
        while (pos < src.length() && src.charAt(pos) != '\'') {
            char ch = src.charAt(pos);
            if (ch == '\\' && pos + 1 < src.length() && (src.charAt(pos + 1) == '\'' || src.charAt(pos + 1) == '\\')) {
                sb.append(src.charAt(pos + 1));
                pos += 2;
                continue;
            }
            if (ch == '\n') {
                line++;
            }
            sb.append(ch);
            pos++;
        }
        if (pos >= src.length()) {
            throw new PhpParseError("Unterminated string", startLine);
        }
        pos++;
        out.add(new Token(Kind.STRING, sb.toString(), start, startLine));
    }

    private void lexDoubleQuoted() {
        int start = pos++;
        int startLine = line;
        pos = lexInterpolated(src, pos, true, start, startLine);
    }

    private record RawLine(int start, int end, int lineNum) {
    }

    private void lexHeredoc() {
        int start = pos;
        int startLine = line;
        pos += 3;
        while (pos < src.length() && (src.charAt(pos) == ' ' || src.charAt(pos) == '\t')) {
            pos++;
        }
        char quote = 0;
        if (pos < src.length() && (src.charAt(pos) == '\'' || src.charAt(pos) == '"')) {
            quote = src.charAt(pos++);
        }
        if (pos >= src.length() || !isIdentStart(src.charAt(pos))) {
            throw new PhpParseError("syntax error, unexpected heredoc label", startLine);
        }
        int lblStart = pos++;
        while (pos < src.length() && isIdentPart(src.charAt(pos))) {
            pos++;
        }
        String label = src.substring(lblStart, pos);
        if (quote != 0) {
            if (pos >= src.length() || src.charAt(pos) != quote) {
                throw new PhpParseError("syntax error, unterminated heredoc label quote", startLine);
            }
            pos++;
        }
        while (pos < src.length() && (src.charAt(pos) == ' ' || src.charAt(pos) == '\t')) {
            pos++;
        }
        if (pos < src.length() && src.charAt(pos) == '\r') {
            pos++;
        }
        if (pos >= src.length() || src.charAt(pos) != '\n') {
            throw new PhpParseError("syntax error, unexpected character after heredoc label", startLine);
        }
        pos++;
        line++;

        List<RawLine> bodyLines = new ArrayList<>();
        String indentStr = "";
        boolean foundClose = false;
        while (pos < src.length()) {
            int p = pos;
            while (p < src.length() && (src.charAt(p) == ' ' || src.charAt(p) == '\t')) {
                p++;
            }
            if (src.startsWith(label, p)) {
                int after = p + label.length();
                if (after == src.length() || !isIdentPart(src.charAt(after))) {
                    indentStr = src.substring(pos, p);
                    if (indentStr.indexOf(' ') >= 0 && indentStr.indexOf('\t') >= 0) {
                        throw new PhpParseError("Invalid indentation - tabs and spaces cannot be mixed", line);
                    }
                    pos = after;
                    foundClose = true;
                    break;
                }
            }
            int nl = src.indexOf('\n', pos);
            if (nl < 0) {
                throw new PhpParseError("Unterminated heredoc", startLine);
            }
            int contentEnd = (nl > pos && src.charAt(nl - 1) == '\r') ? nl - 1 : nl;
            bodyLines.add(new RawLine(pos, contentEnd, line));
            pos = nl + 1;
            line++;
        }
        if (!foundClose) {
            throw new PhpParseError("Unterminated heredoc", startLine);
        }

        int indentLen = indentStr.length();
        char indentChar = indentLen > 0 ? indentStr.charAt(0) : 0;
        StringBuilder rawBody = new StringBuilder();
        for (int i = 0; i < bodyLines.size(); i++) {
            RawLine rl = bodyLines.get(i);
            int s = rl.start();
            int e = rl.end();
            if (indentLen > 0) {
                int matched = 0;
                while (s + matched < e && matched < indentLen) {
                    char ch = src.charAt(s + matched);
                    if (ch == indentChar) {
                        matched++;
                    } else if (ch == ' ' || ch == '\t') {
                        throw new PhpParseError("Invalid indentation - tabs and spaces cannot be mixed", rl.lineNum());
                    } else {
                        throw new PhpParseError(
                                "Invalid body indentation level (expecting an indentation level of at least " + indentLen + ")",
                                rl.lineNum());
                    }
                }
                s += matched;
            }
            rawBody.append(src, s, e);
            if (i < bodyLines.size() - 1) {
                rawBody.append('\n');
            }
        }

        if (quote == '\'') {
            out.add(new Token(Kind.STRING, rawBody.toString(), start, startLine));
        } else {
            lexInterpolated(rawBody.toString(), 0, false, start, startLine);
        }
    }

    private int lexInterpolated(String text, int p, boolean stopAtQuote, int tokenStart, int startLine) {
        List<Part> parts = new ArrayList<>();
        StringBuilder lit = new StringBuilder();
        while (p < text.length()) {
            char ch = text.charAt(p);
            if (stopAtQuote && ch == '"') {
                p++;
                flush(lit, parts);
                emitInterpolatedToken(parts, tokenStart, startLine);
                return p;
            }
            if (ch == '\\' && p + 1 < text.length()) {
                if (isByteEscape(text, p)) {
                    p = appendByteEscapes(text, p, lit);
                    continue;
                }
                if (text.startsWith("u{", p + 1) && text.indexOf('}', p + 3) > p + 3) {
                    int close = text.indexOf('}', p + 3);
                    try {
                        lit.appendCodePoint(Integer.parseInt(text.substring(p + 3, close), 16));
                        p = close + 1;
                        continue;
                    } catch (IllegalArgumentException e) {
                        throw new PhpParseError("Invalid UTF-8 codepoint escape sequence", stopAtQuote ? line : startLine);
                    }
                }
                appendEscape(text.charAt(p + 1), lit, stopAtQuote);
                p += 2;
                continue;
            }
            if (ch == '$' && p + 1 < text.length() && isIdentStart(text.charAt(p + 1))) {
                flush(lit, parts);
                int[] nextPos = new int[1];
                parts.add(new Part(Token.PartKind.EXPR, readSimpleInterpolation(text, p, nextPos)));
                p = nextPos[0];
                continue;
            }
            if (ch == '$' && p + 1 < text.length() && text.charAt(p + 1) == '{') {
                flush(lit, parts);
                int close = matchingBrace(text, p + 1, stopAtQuote);
                String inner = text.substring(p + 2, close).trim();
                if (isDollarBraceVar(inner)) {
                    parts.add(new Part(Token.PartKind.EXPR, "$" + inner));
                } else {
                    parts.add(new Part(Token.PartKind.DYNAMIC_VAR, inner));
                }
                p = close + 1;
                continue;
            }
            if (ch == '{' && p + 1 < text.length() && text.charAt(p + 1) == '$') {
                flush(lit, parts);
                int close = matchingBrace(text, p, stopAtQuote);
                parts.add(new Part(Token.PartKind.EXPR, text.substring(p + 1, close)));
                p = close + 1;
                continue;
            }
            if (stopAtQuote && ch == '\n') {
                line++;
            }
            lit.append(ch);
            p++;
        }
        if (stopAtQuote) {
            throw new PhpParseError("Unterminated string", startLine);
        }
        flush(lit, parts);
        emitInterpolatedToken(parts, tokenStart, startLine);
        return p;
    }

    private void emitInterpolatedToken(List<Part> parts, int start, int startLine) {
        boolean literal = parts.stream().noneMatch(Part::isExpr);
        if (literal) {
            String s = parts.isEmpty() ? "" : parts.get(0).value();
            out.add(new Token(Kind.STRING, s, start, startLine));
        } else {
            out.add(new Token(Kind.TEMPLATE, "", start, startLine, parts));
        }
    }

    /** "$name", "$name[key]", "$name->prop", or "$name?->prop" inside an interpolated string. */
    private static String readSimpleInterpolation(String text, int start, int[] nextPos) {
        int p = start + 1;
        while (p < text.length() && isIdentPart(text.charAt(p))) {
            p++;
        }
        if (p < text.length() && text.charAt(p) == '[') {
            int close = text.indexOf(']', p);
            if (close > 0) {
                String key = text.substring(p + 1, close);
                String varPart = text.substring(start, p);
                nextPos[0] = close + 1;
                // Unquoted keys in simple syntax are string literals: "$a[name]".
                boolean bare = !key.isEmpty() && isIdentStart(key.charAt(0));
                return varPart + "[" + (bare ? "'" + key + "'" : key) + "]";
            }
        } else if (p + 2 < text.length() && text.charAt(p) == '-' && text.charAt(p + 1) == '>'
                && isIdentStart(text.charAt(p + 2))) {
            p += 2;
            while (p < text.length() && isIdentPart(text.charAt(p))) {
                p++;
            }
        } else if (p + 3 < text.length() && text.startsWith("?->", p) && isIdentStart(text.charAt(p + 3))) {
            p += 3;
            while (p < text.length() && isIdentPart(text.charAt(p))) {
                p++;
            }
        }
        nextPos[0] = p;
        return text.substring(start, p);
    }

    /** True for "${name}" or "${name[...]}" (which refer directly to $name / $name[...]). */
    private static boolean isDollarBraceVar(String inner) {
        if (inner.isEmpty() || !isIdentStart(inner.charAt(0))) {
            return false;
        }
        int i = 1;
        while (i < inner.length() && isIdentPart(inner.charAt(i))) {
            i++;
        }
        if (i == inner.length()) {
            return true;
        }
        if (inner.charAt(i) != '[') {
            return false;
        }
        int depth = 0;
        for (int j = i; j < inner.length(); j++) {
            char ch = inner.charAt(j);
            if (ch == '\'' || ch == '"') {
                j = skipQuoted(inner, j, ch);
            } else if (ch == '[') {
                depth++;
            } else if (ch == ']') {
                depth--;
                if (depth == 0) {
                    return j == inner.length() - 1;
                }
            }
        }
        return false;
    }

    private static int skipQuoted(String s, int start, char quote) {
        for (int i = start + 1; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\') {
                i++;
            } else if (ch == quote) {
                return i;
            }
        }
        return s.length() - 1;
    }

    private int matchingBrace(String text, int open, boolean countLines) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\'' || ch == '"') {
                int end = skipQuoted(text, i, ch);
                if (countLines) {
                    countLines(text.substring(i, end + 1));
                }
                i = end;
            } else if (ch == '{') {
                depth++;
            } else if (ch == '}' && --depth == 0) {
                return i;
            } else if (countLines && ch == '\n') {
                line++;
            }
        }
        throw new PhpParseError("Unterminated {$...} in string", line);
    }

    /** At a backslash: is this an octal (\\101) or hex (\\x41) escape, which PHP turns into a byte? */
    private static boolean isByteEscape(String text, int p) {
        char e = p + 1 < text.length() ? text.charAt(p + 1) : 0;
        if (e >= '0' && e <= '7') {
            // a lone \\0 is still the NUL byte, which this also produces
            return true;
        }
        return e == 'x' && p + 2 < text.length() && Character.digit(text.charAt(p + 2), 16) >= 0;
    }

    /**
     * Decodes a run of consecutive octal and hex escapes into bytes. Strings are Java strings
     * for now, so a run that is valid UTF-8 (such as "\\xC2\\xA0") becomes those characters and
     * anything else becomes one char per byte.
     */
    private static int appendByteEscapes(String text, int p, StringBuilder lit) {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        while (p < text.length() && text.charAt(p) == '\\' && isByteEscape(text, p)) {
            int i = p + 1;
            int value;
            if (text.charAt(i) == 'x') {
                int end = i + 1;
                while (end < text.length() && end < i + 3 && Character.digit(text.charAt(end), 16) >= 0) {
                    end++;
                }
                value = Integer.parseInt(text.substring(i + 1, end), 16);
                p = end;
            } else {
                int end = i;
                while (end < text.length() && end < i + 3 && text.charAt(end) >= '0' && text.charAt(end) <= '7') {
                    end++;
                }
                value = Integer.parseInt(text.substring(i, end), 8) & 0xFF;
                p = end;
            }
            bytes.write(value);
        }
        byte[] b = bytes.toByteArray();
        java.nio.charset.CharsetDecoder utf8 = java.nio.charset.StandardCharsets.UTF_8.newDecoder();
        try {
            lit.append(utf8.decode(java.nio.ByteBuffer.wrap(b)));
        } catch (java.nio.charset.CharacterCodingException e) {
            for (byte x : b) {
                lit.append((char) (x & 0xFF));
            }
        }
        return p;
    }

    private static void appendEscape(char e, StringBuilder sb, boolean unescapeDoubleQuote) {
        switch (e) {
            case 'n' -> sb.append('\n');
            case 't' -> sb.append('\t');
            case 'r' -> sb.append('\r');
            case 'v' -> sb.append('\u000B');
            case 'e' -> sb.append('\u001B');
            case 'f' -> sb.append('\f');
            case '\\' -> sb.append('\\');
            case '$' -> sb.append('$');
            case '"' -> sb.append(unescapeDoubleQuote ? "\"" : "\\\"");
            default -> sb.append('\\').append(e);
        }
    }

    private static void flush(StringBuilder lit, List<Part> parts) {
        if (!lit.isEmpty()) {
            parts.add(new Part(false, lit.toString()));
            lit.setLength(0);
        }
    }

    private void countLines(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                line++;
            }
        }
    }
}
