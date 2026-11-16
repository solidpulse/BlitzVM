package dev.phpruntime.builtins;

import dev.phpruntime.runtime.PhpStrings;

import java.sql.SQLException;
import java.util.HexFormat;

/**
 * Connector/J sends Java SQL text as UTF-8. Preserve binary string literals by replacing
 * only literals containing invalid UTF-8 with an equivalent MySQL hexadecimal literal.
 * Like mysqli_real_escape_string, this scanner assumes normal MySQL backslash escapes.
 */
final class MysqliSql {
    private MysqliSql() {
    }

    static String toJava(String sql) throws SQLException {
        if (PhpStrings.isUtf8(sql)) {
            return PhpStrings.toJava(sql);
        }
        StringBuilder out = new StringBuilder(sql.length());
        int copied = 0;
        for (int i = 0; i < sql.length();) {
            char c = sql.charAt(i);
            if (c == '#' || c == '-' && i + 2 < sql.length() && sql.charAt(i + 1) == '-'
                    && sql.charAt(i + 2) <= ' ') {
                int end = sql.indexOf('\n', i);
                i = end < 0 ? sql.length() : end + 1;
            } else if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? sql.length() : end + 2;
            } else if (c == '`') {
                i = quotedEnd(sql, i, '`');
            } else if (c == '\'' || c == '"') {
                int end = quotedEnd(sql, i, c);
                String literal = sql.substring(i + 1, end - 1);
                if (!PhpStrings.isUtf8(literal)) {
                    int start = i;
                    var introducer = java.util.regex.Pattern.compile("(?:_[A-Za-z0-9]+|[nN])\\s*$")
                            .matcher(sql.substring(copied, i));
                    if (introducer.find()) {
                        int at = copied + introducer.start();
                        if (at == 0 || !Character.isLetterOrDigit(sql.charAt(at - 1)) && sql.charAt(at - 1) != '_') {
                            start = at;
                        }
                    }
                    out.append(sql, copied, start);
                    out.append("X'").append(HexFormat.of().formatHex(PhpStrings.bytes(unescape(literal, c)))).append('\'');
                    copied = end;
                }
                i = end;
            } else {
                i++;
            }
        }
        out.append(sql, copied, sql.length());
        String rewritten = out.toString();
        if (!PhpStrings.isUtf8(rewritten)) {
            // An invalid identifier/comment cannot be sent exactly through the driver's text API.
            throw new SQLException("Invalid UTF-8 outside a SQL string literal", "HY000", 1300);
        }
        return PhpStrings.toJava(rewritten);
    }

    private static int quotedEnd(String sql, int start, char quote) throws SQLException {
        for (int i = start + 1; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\\' && quote != '`' && i + 1 < sql.length()) {
                i++;
            } else if (c == quote) {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                    i++;
                } else {
                    return i + 1;
                }
            }
        }
        throw new SQLException("Unterminated SQL string literal", "42000", 1064);
    }

    private static String unescape(String literal, char quote) {
        StringBuilder out = new StringBuilder(literal.length());
        for (int i = 0; i < literal.length(); i++) {
            char c = literal.charAt(i);
            if (c == quote && i + 1 < literal.length() && literal.charAt(i + 1) == quote) {
                out.append(quote);
                i++;
            } else if (c == '\\' && i + 1 < literal.length()) {
                char next = literal.charAt(++i);
                switch (next) {
                    case '0' -> out.append('\0');
                    case 'b' -> out.append('\b');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'Z' -> out.append((char) 26);
                    case '%', '_' -> out.append('\\').append(next);
                    default -> out.append(next);
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
