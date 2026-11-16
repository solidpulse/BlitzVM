package dev.phpruntime.parser;

import java.util.List;

/**
 * A lexical token. {@code text} holds the identifier/operator/literal text; for
 * interpolated strings {@code parts} holds alternating literal and expression pieces.
 */
public record Token(Kind kind, String text, int offset, int line, List<Part> parts) {

    public enum Kind {
        INLINE_HTML,   // text outside <?php ?>
        OPEN_ECHO,     // "<?=" behaves like "echo"
        CLOSE_TAG,     // "?>" acts as a statement terminator
        VARIABLE,      // $name (text excludes the '$')
        IDENT,         // names and keywords
        INT,
        FLOAT,
        STRING,        // fully literal string
        TEMPLATE,      // double-quoted string or heredoc with interpolation
        OP,            // operators and punctuation
        EOF
    }

    public enum PartKind {
        LITERAL,
        EXPR,
        DYNAMIC_VAR
    }

    /** Piece of an interpolated string: literal text, PHP expression source, or ${expr} variable name. */
    public record Part(PartKind kind, String value) {
        public Part(boolean isExpr, String value) {
            this(isExpr ? PartKind.EXPR : PartKind.LITERAL, value);
        }

        public boolean isExpr() {
            return kind != PartKind.LITERAL;
        }
    }

    public Token(Kind kind, String text, int offset, int line) {
        this(kind, text, offset, line, List.of());
    }

    public boolean is(Kind k, String t) {
        return kind == k && text.equals(t);
    }

    public boolean isOp(String t) {
        return kind == Kind.OP && text.equals(t);
    }

    public boolean isKeyword(String kw) {
        return kind == Kind.IDENT && text.equalsIgnoreCase(kw);
    }

    @Override
    public String toString() {
        return kind + "(" + text + ")@" + line;
    }
}
