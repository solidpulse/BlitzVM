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
        String name = src.substring(start, pos);
        // Namespaced names like \strlen resolve to the global function.
        return name.startsWith("\\") ? name.substring(1) : name;
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
        List<Part> parts = new ArrayList<>();
        StringBuilder lit = new StringBuilder();
        while (pos < src.length() && src.charAt(pos) != '"') {
            char ch = src.charAt(pos);
            if (ch == '\\' && pos + 1 < src.length()) {
                if (isByteEscape()) {
                    appendByteEscapes(lit);
                    continue;
                }
                if (src.startsWith("u{", pos + 1) && src.indexOf('}', pos + 3) > pos + 3) {
                    int close = src.indexOf('}', pos + 3);
                    try {
                        lit.appendCodePoint(Integer.parseInt(src.substring(pos + 3, close), 16));
                        pos = close + 1;
                        continue;
                    } catch (IllegalArgumentException e) {
                        throw new PhpParseError("Invalid UTF-8 codepoint escape sequence", line);
                    }
                }
                pos += 2;
                appendEscape(src.charAt(pos - 1), lit);
                continue;
            }
            if (ch == '$' && pos + 1 < src.length() && isIdentStart(src.charAt(pos + 1))) {
                flush(lit, parts);
                parts.add(new Part(true, readSimpleInterpolation()));
                continue;
            }
            if (ch == '{' && pos + 1 < src.length() && src.charAt(pos + 1) == '$') {
                flush(lit, parts);
                int close = matchingBrace(pos);
                parts.add(new Part(true, src.substring(pos + 1, close)));
                pos = close + 1;
                continue;
            }
            if (ch == '\n') {
                line++;
            }
            lit.append(ch);
            pos++;
        }
        if (pos >= src.length()) {
            throw new PhpParseError("Unterminated string", startLine);
        }
        pos++;
        flush(lit, parts);
        boolean literal = parts.stream().noneMatch(Part::isExpr);
        if (literal) {
            String s = parts.isEmpty() ? "" : parts.get(0).value();
            out.add(new Token(Kind.STRING, s, start, startLine));
        } else {
            out.add(new Token(Kind.TEMPLATE, "", start, startLine, parts));
        }
    }

    /** "$name", "$name[key]" or "$name->prop" inside a double-quoted string. */
    private String readSimpleInterpolation() {
        int start = pos++;
        while (pos < src.length() && isIdentPart(src.charAt(pos))) {
            pos++;
        }
        if (pos < src.length() && src.charAt(pos) == '[') {
            int close = src.indexOf(']', pos);
            if (close > 0) {
                String key = src.substring(pos + 1, close);
                pos = close + 1;
                String varPart = src.substring(start, src.lastIndexOf('[', close));
                // Unquoted keys in simple syntax are string literals: "$a[name]".
                boolean bare = !key.isEmpty() && isIdentStart(key.charAt(0));
                return varPart + "[" + (bare ? "'" + key + "'" : key) + "]";
            }
        }
        return src.substring(start, pos);
    }

    private int matchingBrace(int open) {
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char ch = src.charAt(i);
            if (ch == '{') {
                depth++;
            } else if (ch == '}' && --depth == 0) {
                return i;
            }
        }
        throw new PhpParseError("Unterminated {$...} in string", line);
    }

    /** At a backslash: is this an octal (\\101) or hex (\\x41) escape, which PHP turns into a byte? */
    private boolean isByteEscape() {
        char e = pos + 1 < src.length() ? src.charAt(pos + 1) : 0;
        if (e >= '0' && e <= '7') {
            // a lone \\0 is still the NUL byte, which this also produces
            return true;
        }
        return e == 'x' && pos + 2 < src.length() && Character.digit(src.charAt(pos + 2), 16) >= 0;
    }

    /**
     * Decodes a run of consecutive octal and hex escapes into bytes. Strings are Java strings
     * for now, so a run that is valid UTF-8 (such as "\\xC2\\xA0") becomes those characters and
     * anything else becomes one char per byte.
     */
    private void appendByteEscapes(StringBuilder lit) {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        while (pos < src.length() && src.charAt(pos) == '\\' && isByteEscape()) {
            int i = pos + 1;
            int value;
            if (src.charAt(i) == 'x') {
                int end = i + 1;
                while (end < src.length() && end < i + 3 && Character.digit(src.charAt(end), 16) >= 0) {
                    end++;
                }
                value = Integer.parseInt(src.substring(i + 1, end), 16);
                pos = end;
            } else {
                int end = i;
                while (end < src.length() && end < i + 3 && src.charAt(end) >= '0' && src.charAt(end) <= '7') {
                    end++;
                }
                value = Integer.parseInt(src.substring(i, end), 8) & 0xFF;
                pos = end;
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
    }

    private static void appendEscape(char e, StringBuilder sb) {
        switch (e) {
            case 'n' -> sb.append('\n');
            case 't' -> sb.append('\t');
            case 'r' -> sb.append('\r');
            case 'v' -> sb.append('\u000B');
            case 'e' -> sb.append('\u001B');
            case 'f' -> sb.append('\f');
            case '\\' -> sb.append('\\');
            case '$' -> sb.append('$');
            case '"' -> sb.append('"');
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
