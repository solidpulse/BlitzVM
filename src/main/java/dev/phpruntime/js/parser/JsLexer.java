package dev.phpruntime.js.parser;

import java.util.ArrayList;
import java.util.List;

/** Turns JavaScript source into tokens. Template literals keep their expression sources for the parser. */
final class JsLexer {

    enum Kind { NUM, STR, TEMPLATE, IDENT, PUNCT, EOF }

    /** One piece of a template literal: either cooked text or an expression's source. */
    record TemplatePart(String text, String expression, int line) {
    }

    record Token(Kind kind, String text, Object value, List<TemplatePart> parts, int line) {
        boolean is(String punct) {
            return (kind == Kind.PUNCT || kind == Kind.IDENT) && text.equals(punct);
        }
    }

    private static final String[] PUNCTUATORS = {
            "===", "!==", "...", "**=", "=>", "==", "!=", "<=", ">=", "&&", "||", "??", "++", "--",
            "+=", "-=", "*=", "/=", "%=", "**", "?.",
            "{", "}", "(", ")", "[", "]", ";", ",", ".", "<", ">", "+", "-", "*", "/", "%", "=", "!", "?", ":",
    };

    private final String src;
    private int pos;
    private int line;

    JsLexer(String src, int firstLine) {
        this.src = src;
        this.line = firstLine;
    }

    List<Token> lex() {
        List<Token> out = new ArrayList<>();
        while (true) {
            skipSpaceAndComments();
            if (pos >= src.length()) {
                out.add(new Token(Kind.EOF, "<end of input>", null, null, line));
                return out;
            }
            out.add(next());
        }
    }

    private void skipSpaceAndComments() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == '\n') {
                line++;
                pos++;
            } else if (Character.isWhitespace(c)) {
                pos++;
            } else if (src.startsWith("//", pos)) {
                while (pos < src.length() && src.charAt(pos) != '\n') {
                    pos++;
                }
            } else if (src.startsWith("/*", pos)) {
                int end = src.indexOf("*/", pos + 2);
                if (end < 0) {
                    throw new JsParseError("Unterminated comment", line);
                }
                for (int i = pos; i < end; i++) {
                    if (src.charAt(i) == '\n') {
                        line++;
                    }
                }
                pos = end + 2;
            } else if (src.startsWith("#!", pos) && pos == 0) {
                while (pos < src.length() && src.charAt(pos) != '\n') {
                    pos++;
                }
            } else {
                return;
            }
        }
    }

    private Token next() {
        char c = src.charAt(pos);
        int startLine = line;
        if (Character.isDigit(c) || (c == '.' && pos + 1 < src.length() && Character.isDigit(src.charAt(pos + 1)))) {
            return number(startLine);
        }
        if (Character.isJavaIdentifierStart(c)) {
            int start = pos;
            while (pos < src.length() && Character.isJavaIdentifierPart(src.charAt(pos))) {
                pos++;
            }
            String id = src.substring(start, pos);
            return new Token(Kind.IDENT, id, null, null, startLine);
        }
        if (c == '\'' || c == '"') {
            pos++;
            String s = stringBody(c);
            return new Token(Kind.STR, s, s, null, startLine);
        }
        if (c == '`') {
            pos++;
            return template(startLine);
        }
        for (String p : PUNCTUATORS) {
            if (src.startsWith(p, pos)) {
                pos += p.length();
                return new Token(Kind.PUNCT, p, null, null, startLine);
            }
        }
        throw new JsParseError("Invalid or unexpected token '" + c + "'", line);
    }

    private Token number(int startLine) {
        int start = pos;
        if (src.startsWith("0x", pos) || src.startsWith("0X", pos)) {
            pos += 2;
            while (pos < src.length() && Character.digit(src.charAt(pos), 16) >= 0) {
                pos++;
            }
            double v = Long.parseLong(src.substring(start + 2, pos), 16);
            return new Token(Kind.NUM, src.substring(start, pos), numberValue(v), null, startLine);
        }
        while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '_')) {
            pos++;
        }
        if (pos < src.length() && src.charAt(pos) == '.') {
            pos++;
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                pos++;
            }
        }
        if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
            int save = pos;
            pos++;
            if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) {
                pos++;
            }
            if (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                    pos++;
                }
            } else {
                pos = save;
            }
        }
        String text = src.substring(start, pos);
        return new Token(Kind.NUM, text, numberValue(Double.parseDouble(text.replace("_", ""))), null, startLine);
    }

    private static Object numberValue(double v) {
        int i = (int) v;
        return i == v ? (Object) i : (Object) v;
    }

    private String stringBody(char quote) {
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length() || src.charAt(pos) == '\n') {
                throw new JsParseError("Invalid or unexpected token (unterminated string)", line);
            }
            char c = src.charAt(pos++);
            if (c == quote) {
                return sb.toString();
            }
            if (c == '\\') {
                escape(sb);
            } else {
                sb.append(c);
            }
        }
    }

    private void escape(StringBuilder sb) {
        if (pos >= src.length()) {
            throw new JsParseError("Invalid escape at end of input", line);
        }
        char e = src.charAt(pos++);
        switch (e) {
            case 'n' -> sb.append('\n');
            case 't' -> sb.append('\t');
            case 'r' -> sb.append('\r');
            case 'b' -> sb.append('\b');
            case 'f' -> sb.append('\f');
            case 'v' -> sb.append('\u000B');
            case '0' -> sb.append('\0');
            case 'x' -> {
                sb.append((char) Integer.parseInt(src.substring(pos, pos + 2), 16));
                pos += 2;
            }
            case 'u' -> {
                if (pos < src.length() && src.charAt(pos) == '{') {
                    int end = src.indexOf('}', pos);
                    sb.appendCodePoint(Integer.parseInt(src.substring(pos + 1, end), 16));
                    pos = end + 1;
                } else {
                    sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                    pos += 4;
                }
            }
            case '\n' -> line++; // line continuation
            default -> sb.append(e);
        }
    }

    private Token template(int startLine) {
        List<TemplatePart> parts = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) {
                throw new JsParseError("Unterminated template literal", startLine);
            }
            char c = src.charAt(pos);
            if (c == '`') {
                pos++;
                parts.add(new TemplatePart(sb.toString(), null, line));
                return new Token(Kind.TEMPLATE, "`", null, parts, startLine);
            }
            if (c == '\\') {
                pos++;
                escape(sb);
            } else if (c == '$' && pos + 1 < src.length() && src.charAt(pos + 1) == '{') {
                parts.add(new TemplatePart(sb.toString(), null, line));
                sb.setLength(0);
                pos += 2;
                int exprLine = line;
                int start = pos;
                int depth = 1;
                while (depth > 0) {
                    if (pos >= src.length()) {
                        throw new JsParseError("Unterminated template expression", exprLine);
                    }
                    char d = src.charAt(pos);
                    if (d == '{') {
                        depth++;
                    } else if (d == '}') {
                        depth--;
                    } else if (d == '\'' || d == '"') {
                        pos++;
                        stringBody(d);
                        continue;
                    } else if (d == '\n') {
                        line++;
                    }
                    pos++;
                }
                parts.add(new TemplatePart(null, src.substring(start, pos - 1), exprLine));
            } else {
                if (c == '\n') {
                    line++;
                }
                sb.append(c);
                pos++;
            }
        }
    }
}
