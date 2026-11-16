package dev.phpruntime.builtins;

import dev.phpruntime.runtime.PhpArray;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PHP's tokenizer for token_get_all(). Mirrors Zend's Zend/zend_language_scanner.l: the
 * emitted token ids, texts and start lines must match PHP byte for byte.
 */
final class Tokenizer {

    static final int T_ABSTRACT = 322;
    static final int T_AMPERSAND_FOLLOWED_BY_VAR_OR_VARARG = 409;
    static final int T_AMPERSAND_NOT_FOLLOWED_BY_VAR_OR_VARARG = 410;
    static final int T_AND_EQUAL = 362;
    static final int T_ARRAY = 344;
    static final int T_ARRAY_CAST = 384;
    static final int T_AS = 301;
    static final int T_ATTRIBUTE = 355;
    static final int T_BAD_CHARACTER = 411;
    static final int T_BOOLEAN_AND = 369;
    static final int T_BOOLEAN_OR = 368;
    static final int T_BOOL_CAST = 386;
    static final int T_BREAK = 307;
    static final int T_CALLABLE = 345;
    static final int T_CASE = 304;
    static final int T_CATCH = 315;
    static final int T_CLASS = 336;
    static final int T_CLASS_C = 349;
    static final int T_CLONE = 285;
    static final int T_CLOSE_TAG = 396;
    static final int T_COALESCE = 405;
    static final int T_COALESCE_EQUAL = 367;
    static final int T_COMMENT = 392;
    static final int T_CONCAT_EQUAL = 360;
    static final int T_CONST = 312;
    static final int T_CONSTANT_ENCAPSED_STRING = 269;
    static final int T_CONTINUE = 308;
    static final int T_CURLY_OPEN = 401;
    static final int T_DEC = 380;
    static final int T_DECLARE = 299;
    static final int T_DEFAULT = 305;
    static final int T_DIR = 348;
    static final int T_DIV_EQUAL = 359;
    static final int T_DNUMBER = 261;
    static final int T_DO = 292;
    static final int T_DOC_COMMENT = 393;
    static final int T_DOLLAR_OPEN_CURLY_BRACES = 400;
    static final int T_DOUBLE_ARROW = 391;
    static final int T_DOUBLE_CAST = 382;
    static final int T_DOUBLE_COLON = 402;
    static final int T_ECHO = 291;
    static final int T_ELLIPSIS = 404;
    static final int T_ELSE = 289;
    static final int T_ELSEIF = 288;
    static final int T_EMPTY = 334;
    static final int T_ENCAPSED_AND_WHITESPACE = 268;
    static final int T_ENDDECLARE = 300;
    static final int T_ENDFOR = 296;
    static final int T_ENDFOREACH = 298;
    static final int T_ENDIF = 290;
    static final int T_ENDSWITCH = 303;
    static final int T_ENDWHILE = 294;
    static final int T_END_HEREDOC = 399;
    static final int T_ENUM = 339;
    static final int T_EVAL = 274;
    static final int T_EXIT = 286;
    static final int T_EXTENDS = 340;
    static final int T_FILE = 347;
    static final int T_FINAL = 323;
    static final int T_FINALLY = 316;
    static final int T_FN = 311;
    static final int T_FOR = 295;
    static final int T_FOREACH = 297;
    static final int T_FUNCTION = 310;
    static final int T_FUNC_C = 352;
    static final int T_GLOBAL = 320;
    static final int T_GOTO = 309;
    static final int T_HALT_COMPILER = 335;
    static final int T_IF = 287;
    static final int T_IMPLEMENTS = 341;
    static final int T_INC = 379;
    static final int T_INCLUDE = 272;
    static final int T_INCLUDE_ONCE = 273;
    static final int T_INLINE_HTML = 267;
    static final int T_INSTANCEOF = 283;
    static final int T_INSTEADOF = 319;
    static final int T_INTERFACE = 338;
    static final int T_INT_CAST = 381;
    static final int T_ISSET = 333;
    static final int T_IS_EQUAL = 370;
    static final int T_IS_GREATER_OR_EQUAL = 375;
    static final int T_IS_IDENTICAL = 372;
    static final int T_IS_NOT_EQUAL = 371;
    static final int T_IS_NOT_IDENTICAL = 373;
    static final int T_IS_SMALLER_OR_EQUAL = 374;
    static final int T_LINE = 346;
    static final int T_LIST = 343;
    static final int T_LNUMBER = 260;
    static final int T_LOGICAL_AND = 279;
    static final int T_LOGICAL_OR = 277;
    static final int T_LOGICAL_XOR = 278;
    static final int T_MATCH = 306;
    static final int T_METHOD_C = 351;
    static final int T_MINUS_EQUAL = 357;
    static final int T_MOD_EQUAL = 361;
    static final int T_MUL_EQUAL = 358;
    static final int T_NAMESPACE = 342;
    static final int T_NAME_FULLY_QUALIFIED = 263;
    static final int T_NAME_QUALIFIED = 265;
    static final int T_NAME_RELATIVE = 264;
    static final int T_NEW = 284;
    static final int T_NS_C = 354;
    static final int T_NS_SEPARATOR = 403;
    static final int T_NULLSAFE_OBJECT_OPERATOR = 390;
    static final int T_NUM_STRING = 271;
    static final int T_OBJECT_CAST = 385;
    static final int T_OBJECT_OPERATOR = 389;
    static final int T_OPEN_TAG = 394;
    static final int T_OPEN_TAG_WITH_ECHO = 395;
    static final int T_OR_EQUAL = 363;
    static final int T_PAAMAYIM_NEKUDOTAYIM = 402;
    static final int T_PIPE = 408;
    static final int T_PLUS_EQUAL = 356;
    static final int T_POW = 406;
    static final int T_POW_EQUAL = 407;
    static final int T_PRINT = 280;
    static final int T_PRIVATE = 324;
    static final int T_PRIVATE_SET = 327;
    static final int T_PROPERTY_C = 353;
    static final int T_PROTECTED = 325;
    static final int T_PROTECTED_SET = 328;
    static final int T_PUBLIC = 326;
    static final int T_PUBLIC_SET = 329;
    static final int T_READONLY = 330;
    static final int T_REQUIRE = 275;
    static final int T_REQUIRE_ONCE = 276;
    static final int T_RETURN = 313;
    static final int T_SL = 377;
    static final int T_SL_EQUAL = 365;
    static final int T_SPACESHIP = 376;
    static final int T_SR = 378;
    static final int T_SR_EQUAL = 366;
    static final int T_START_HEREDOC = 398;
    static final int T_STATIC = 321;
    static final int T_STRING = 262;
    static final int T_STRING_CAST = 383;
    static final int T_STRING_VARNAME = 270;
    static final int T_SWITCH = 302;
    static final int T_THROW = 317;
    static final int T_TRAIT = 337;
    static final int T_TRAIT_C = 350;
    static final int T_TRY = 314;
    static final int T_UNSET = 332;
    static final int T_UNSET_CAST = 387;
    static final int T_USE = 318;
    static final int T_VAR = 331;
    static final int T_VARIABLE = 266;
    static final int T_VOID_CAST = 388;
    static final int T_WHILE = 293;
    static final int T_WHITESPACE = 397;
    static final int T_XOR_EQUAL = 364;
    static final int T_YIELD = 281;
    static final int T_YIELD_FROM = 282;

    private static final Map<Integer, String> NAMES = new HashMap<>();
    private static final Map<String, Integer> KEYWORDS = new HashMap<>();
    private static final Map<String, Integer> OPERATORS = new LinkedHashMap<>();

    static {
        NAMES.put(T_ABSTRACT, "T_ABSTRACT");
        NAMES.put(T_AMPERSAND_FOLLOWED_BY_VAR_OR_VARARG, "T_AMPERSAND_FOLLOWED_BY_VAR_OR_VARARG");
        NAMES.put(T_AMPERSAND_NOT_FOLLOWED_BY_VAR_OR_VARARG, "T_AMPERSAND_NOT_FOLLOWED_BY_VAR_OR_VARARG");
        NAMES.put(T_AND_EQUAL, "T_AND_EQUAL");
        NAMES.put(T_ARRAY, "T_ARRAY");
        NAMES.put(T_ARRAY_CAST, "T_ARRAY_CAST");
        NAMES.put(T_AS, "T_AS");
        NAMES.put(T_ATTRIBUTE, "T_ATTRIBUTE");
        NAMES.put(T_BAD_CHARACTER, "T_BAD_CHARACTER");
        NAMES.put(T_BOOLEAN_AND, "T_BOOLEAN_AND");
        NAMES.put(T_BOOLEAN_OR, "T_BOOLEAN_OR");
        NAMES.put(T_BOOL_CAST, "T_BOOL_CAST");
        NAMES.put(T_BREAK, "T_BREAK");
        NAMES.put(T_CALLABLE, "T_CALLABLE");
        NAMES.put(T_CASE, "T_CASE");
        NAMES.put(T_CATCH, "T_CATCH");
        NAMES.put(T_CLASS, "T_CLASS");
        NAMES.put(T_CLASS_C, "T_CLASS_C");
        NAMES.put(T_CLONE, "T_CLONE");
        NAMES.put(T_CLOSE_TAG, "T_CLOSE_TAG");
        NAMES.put(T_COALESCE, "T_COALESCE");
        NAMES.put(T_COALESCE_EQUAL, "T_COALESCE_EQUAL");
        NAMES.put(T_COMMENT, "T_COMMENT");
        NAMES.put(T_CONCAT_EQUAL, "T_CONCAT_EQUAL");
        NAMES.put(T_CONST, "T_CONST");
        NAMES.put(T_CONSTANT_ENCAPSED_STRING, "T_CONSTANT_ENCAPSED_STRING");
        NAMES.put(T_CONTINUE, "T_CONTINUE");
        NAMES.put(T_CURLY_OPEN, "T_CURLY_OPEN");
        NAMES.put(T_DEC, "T_DEC");
        NAMES.put(T_DECLARE, "T_DECLARE");
        NAMES.put(T_DEFAULT, "T_DEFAULT");
        NAMES.put(T_DIR, "T_DIR");
        NAMES.put(T_DIV_EQUAL, "T_DIV_EQUAL");
        NAMES.put(T_DNUMBER, "T_DNUMBER");
        NAMES.put(T_DO, "T_DO");
        NAMES.put(T_DOC_COMMENT, "T_DOC_COMMENT");
        NAMES.put(T_DOLLAR_OPEN_CURLY_BRACES, "T_DOLLAR_OPEN_CURLY_BRACES");
        NAMES.put(T_DOUBLE_ARROW, "T_DOUBLE_ARROW");
        NAMES.put(T_DOUBLE_CAST, "T_DOUBLE_CAST");
        NAMES.put(T_DOUBLE_COLON, "T_DOUBLE_COLON");
        NAMES.put(T_ECHO, "T_ECHO");
        NAMES.put(T_ELLIPSIS, "T_ELLIPSIS");
        NAMES.put(T_ELSE, "T_ELSE");
        NAMES.put(T_ELSEIF, "T_ELSEIF");
        NAMES.put(T_EMPTY, "T_EMPTY");
        NAMES.put(T_ENCAPSED_AND_WHITESPACE, "T_ENCAPSED_AND_WHITESPACE");
        NAMES.put(T_ENDDECLARE, "T_ENDDECLARE");
        NAMES.put(T_ENDFOR, "T_ENDFOR");
        NAMES.put(T_ENDFOREACH, "T_ENDFOREACH");
        NAMES.put(T_ENDIF, "T_ENDIF");
        NAMES.put(T_ENDSWITCH, "T_ENDSWITCH");
        NAMES.put(T_ENDWHILE, "T_ENDWHILE");
        NAMES.put(T_END_HEREDOC, "T_END_HEREDOC");
        NAMES.put(T_ENUM, "T_ENUM");
        NAMES.put(T_EVAL, "T_EVAL");
        NAMES.put(T_EXIT, "T_EXIT");
        NAMES.put(T_EXTENDS, "T_EXTENDS");
        NAMES.put(T_FILE, "T_FILE");
        NAMES.put(T_FINAL, "T_FINAL");
        NAMES.put(T_FINALLY, "T_FINALLY");
        NAMES.put(T_FN, "T_FN");
        NAMES.put(T_FOR, "T_FOR");
        NAMES.put(T_FOREACH, "T_FOREACH");
        NAMES.put(T_FUNCTION, "T_FUNCTION");
        NAMES.put(T_FUNC_C, "T_FUNC_C");
        NAMES.put(T_GLOBAL, "T_GLOBAL");
        NAMES.put(T_GOTO, "T_GOTO");
        NAMES.put(T_HALT_COMPILER, "T_HALT_COMPILER");
        NAMES.put(T_IF, "T_IF");
        NAMES.put(T_IMPLEMENTS, "T_IMPLEMENTS");
        NAMES.put(T_INC, "T_INC");
        NAMES.put(T_INCLUDE, "T_INCLUDE");
        NAMES.put(T_INCLUDE_ONCE, "T_INCLUDE_ONCE");
        NAMES.put(T_INLINE_HTML, "T_INLINE_HTML");
        NAMES.put(T_INSTANCEOF, "T_INSTANCEOF");
        NAMES.put(T_INSTEADOF, "T_INSTEADOF");
        NAMES.put(T_INTERFACE, "T_INTERFACE");
        NAMES.put(T_INT_CAST, "T_INT_CAST");
        NAMES.put(T_ISSET, "T_ISSET");
        NAMES.put(T_IS_EQUAL, "T_IS_EQUAL");
        NAMES.put(T_IS_GREATER_OR_EQUAL, "T_IS_GREATER_OR_EQUAL");
        NAMES.put(T_IS_IDENTICAL, "T_IS_IDENTICAL");
        NAMES.put(T_IS_NOT_EQUAL, "T_IS_NOT_EQUAL");
        NAMES.put(T_IS_NOT_IDENTICAL, "T_IS_NOT_IDENTICAL");
        NAMES.put(T_IS_SMALLER_OR_EQUAL, "T_IS_SMALLER_OR_EQUAL");
        NAMES.put(T_LINE, "T_LINE");
        NAMES.put(T_LIST, "T_LIST");
        NAMES.put(T_LNUMBER, "T_LNUMBER");
        NAMES.put(T_LOGICAL_AND, "T_LOGICAL_AND");
        NAMES.put(T_LOGICAL_OR, "T_LOGICAL_OR");
        NAMES.put(T_LOGICAL_XOR, "T_LOGICAL_XOR");
        NAMES.put(T_MATCH, "T_MATCH");
        NAMES.put(T_METHOD_C, "T_METHOD_C");
        NAMES.put(T_MINUS_EQUAL, "T_MINUS_EQUAL");
        NAMES.put(T_MOD_EQUAL, "T_MOD_EQUAL");
        NAMES.put(T_MUL_EQUAL, "T_MUL_EQUAL");
        NAMES.put(T_NAMESPACE, "T_NAMESPACE");
        NAMES.put(T_NAME_FULLY_QUALIFIED, "T_NAME_FULLY_QUALIFIED");
        NAMES.put(T_NAME_QUALIFIED, "T_NAME_QUALIFIED");
        NAMES.put(T_NAME_RELATIVE, "T_NAME_RELATIVE");
        NAMES.put(T_NEW, "T_NEW");
        NAMES.put(T_NS_C, "T_NS_C");
        NAMES.put(T_NS_SEPARATOR, "T_NS_SEPARATOR");
        NAMES.put(T_NULLSAFE_OBJECT_OPERATOR, "T_NULLSAFE_OBJECT_OPERATOR");
        NAMES.put(T_NUM_STRING, "T_NUM_STRING");
        NAMES.put(T_OBJECT_CAST, "T_OBJECT_CAST");
        NAMES.put(T_OBJECT_OPERATOR, "T_OBJECT_OPERATOR");
        NAMES.put(T_OPEN_TAG, "T_OPEN_TAG");
        NAMES.put(T_OPEN_TAG_WITH_ECHO, "T_OPEN_TAG_WITH_ECHO");
        NAMES.put(T_OR_EQUAL, "T_OR_EQUAL");
        NAMES.put(T_PAAMAYIM_NEKUDOTAYIM, "T_PAAMAYIM_NEKUDOTAYIM");
        NAMES.put(T_PIPE, "T_PIPE");
        NAMES.put(T_PLUS_EQUAL, "T_PLUS_EQUAL");
        NAMES.put(T_POW, "T_POW");
        NAMES.put(T_POW_EQUAL, "T_POW_EQUAL");
        NAMES.put(T_PRINT, "T_PRINT");
        NAMES.put(T_PRIVATE, "T_PRIVATE");
        NAMES.put(T_PRIVATE_SET, "T_PRIVATE_SET");
        NAMES.put(T_PROPERTY_C, "T_PROPERTY_C");
        NAMES.put(T_PROTECTED, "T_PROTECTED");
        NAMES.put(T_PROTECTED_SET, "T_PROTECTED_SET");
        NAMES.put(T_PUBLIC, "T_PUBLIC");
        NAMES.put(T_PUBLIC_SET, "T_PUBLIC_SET");
        NAMES.put(T_READONLY, "T_READONLY");
        NAMES.put(T_REQUIRE, "T_REQUIRE");
        NAMES.put(T_REQUIRE_ONCE, "T_REQUIRE_ONCE");
        NAMES.put(T_RETURN, "T_RETURN");
        NAMES.put(T_SL, "T_SL");
        NAMES.put(T_SL_EQUAL, "T_SL_EQUAL");
        NAMES.put(T_SPACESHIP, "T_SPACESHIP");
        NAMES.put(T_SR, "T_SR");
        NAMES.put(T_SR_EQUAL, "T_SR_EQUAL");
        NAMES.put(T_START_HEREDOC, "T_START_HEREDOC");
        NAMES.put(T_STATIC, "T_STATIC");
        NAMES.put(T_STRING, "T_STRING");
        NAMES.put(T_STRING_CAST, "T_STRING_CAST");
        NAMES.put(T_STRING_VARNAME, "T_STRING_VARNAME");
        NAMES.put(T_SWITCH, "T_SWITCH");
        NAMES.put(T_THROW, "T_THROW");
        NAMES.put(T_TRAIT, "T_TRAIT");
        NAMES.put(T_TRAIT_C, "T_TRAIT_C");
        NAMES.put(T_TRY, "T_TRY");
        NAMES.put(T_UNSET, "T_UNSET");
        NAMES.put(T_UNSET_CAST, "T_UNSET_CAST");
        NAMES.put(T_USE, "T_USE");
        NAMES.put(T_VAR, "T_VAR");
        NAMES.put(T_VARIABLE, "T_VARIABLE");
        NAMES.put(T_VOID_CAST, "T_VOID_CAST");
        NAMES.put(T_WHILE, "T_WHILE");
        NAMES.put(T_WHITESPACE, "T_WHITESPACE");
        NAMES.put(T_XOR_EQUAL, "T_XOR_EQUAL");
        NAMES.put(T_YIELD, "T_YIELD");
        NAMES.put(T_YIELD_FROM, "T_YIELD_FROM");
        String[] keywords = {
            "abstract", "and", "array", "as", "break", "callable", "case", "catch", "class",
            "clone", "const", "continue", "declare", "default", "die", "do", "echo", "else",
            "elseif", "empty", "enddeclare", "endfor", "endforeach", "endif", "endswitch",
            "endwhile", "enum", "eval", "exit", "extends", "final", "finally", "fn", "for",
            "foreach", "function", "global", "goto", "if", "implements", "include",
            "include_once", "instanceof", "insteadof", "interface", "isset", "list", "match",
            "namespace", "new", "or", "print", "private", "protected", "public", "readonly",
            "require", "require_once", "return", "static", "switch", "throw", "trait", "try",
            "unset", "use", "var", "while", "xor", "yield", "__halt_compiler", "__class__",
            "__dir__", "__file__", "__function__", "__line__", "__method__", "__namespace__",
            "__trait__", "__property__",
        };
        int[] keywordTokens = {
            T_ABSTRACT, T_LOGICAL_AND, T_ARRAY, T_AS, T_BREAK, T_CALLABLE, T_CASE, T_CATCH,
            T_CLASS, T_CLONE, T_CONST, T_CONTINUE, T_DECLARE, T_DEFAULT, T_EXIT, T_DO, T_ECHO,
            T_ELSE, T_ELSEIF, T_EMPTY, T_ENDDECLARE, T_ENDFOR, T_ENDFOREACH, T_ENDIF,
            T_ENDSWITCH, T_ENDWHILE, T_ENUM, T_EVAL, T_EXIT, T_EXTENDS, T_FINAL, T_FINALLY,
            T_FN, T_FOR, T_FOREACH, T_FUNCTION, T_GLOBAL, T_GOTO, T_IF, T_IMPLEMENTS,
            T_INCLUDE, T_INCLUDE_ONCE, T_INSTANCEOF, T_INSTEADOF, T_INTERFACE, T_ISSET,
            T_LIST, T_MATCH, T_NAMESPACE, T_NEW, T_LOGICAL_OR, T_PRINT, T_PRIVATE,
            T_PROTECTED, T_PUBLIC, T_READONLY, T_REQUIRE, T_REQUIRE_ONCE, T_RETURN, T_STATIC,
            T_SWITCH, T_THROW, T_TRAIT, T_TRY, T_UNSET, T_USE, T_VAR, T_WHILE, T_LOGICAL_XOR,
            T_YIELD, T_HALT_COMPILER, T_CLASS_C, T_DIR, T_FILE, T_FUNC_C, T_LINE, T_METHOD_C,
            T_NS_C, T_TRAIT_C, T_PROPERTY_C,
        };
        for (int i = 0; i < keywords.length; i++) {
            KEYWORDS.put(keywords[i], keywordTokens[i]);
        }
        OPERATORS.put("**=", T_POW_EQUAL);
        OPERATORS.put("??=", T_COALESCE_EQUAL);
        OPERATORS.put("<<=", T_SL_EQUAL);
        OPERATORS.put(">>=", T_SR_EQUAL);
        OPERATORS.put("===", T_IS_IDENTICAL);
        OPERATORS.put("!==", T_IS_NOT_IDENTICAL);
        OPERATORS.put("<=>", T_SPACESHIP);
        OPERATORS.put("...", T_ELLIPSIS);
        OPERATORS.put("?->", T_NULLSAFE_OBJECT_OPERATOR);
        OPERATORS.put("->", T_OBJECT_OPERATOR);
        OPERATORS.put("::", T_DOUBLE_COLON);
        OPERATORS.put("=>", T_DOUBLE_ARROW);
        OPERATORS.put("++", T_INC);
        OPERATORS.put("--", T_DEC);
        OPERATORS.put("&&", T_BOOLEAN_AND);
        OPERATORS.put("||", T_BOOLEAN_OR);
        OPERATORS.put("??", T_COALESCE);
        OPERATORS.put("**", T_POW);
        OPERATORS.put("<<", T_SL);
        OPERATORS.put(">>", T_SR);
        OPERATORS.put("<=", T_IS_SMALLER_OR_EQUAL);
        OPERATORS.put(">=", T_IS_GREATER_OR_EQUAL);
        OPERATORS.put("==", T_IS_EQUAL);
        OPERATORS.put("!=", T_IS_NOT_EQUAL);
        OPERATORS.put("<>", T_IS_NOT_EQUAL);
        OPERATORS.put("+=", T_PLUS_EQUAL);
        OPERATORS.put("-=", T_MINUS_EQUAL);
        OPERATORS.put("*=", T_MUL_EQUAL);
        OPERATORS.put("/=", T_DIV_EQUAL);
        OPERATORS.put(".=", T_CONCAT_EQUAL);
        OPERATORS.put("%=", T_MOD_EQUAL);
        OPERATORS.put("&=", T_AND_EQUAL);
        OPERATORS.put("|=", T_OR_EQUAL);
        OPERATORS.put("^=", T_XOR_EQUAL);
    }

    private Tokenizer() {
    }

    static String tokenName(long id) {
        return NAMES.getOrDefault((int) id, "UNKNOWN");
    }

    /** token_get_all: an array of single-character strings or [id, text, line] triples. */
    static PhpArray tokenize(String source) {
        PhpArray out = new PhpArray();
        new Scanner(source, out).run();
        return out;
    }

    private static final class Scanner {
        private final String src;
        private final PhpArray out;
        private int pos;
        private int line = 1;
        private boolean code;
        private boolean haltPending;
        /** After -> or ?-> the next identifier is a property name (T_STRING), even if a keyword. */
        private boolean lookingForProperty;

        Scanner(String src, PhpArray out) {
            this.src = src;
            this.out = out;
        }

        void run() {
            while (pos < src.length()) {
                if (!code) {
                    lexInlineHtml();
                } else {
                    lexCodeLoop();
                }
            }
        }

        // ---------------------------------------------------------- inline HTML

        private void lexInlineHtml() {
            int start = pos;
            int startLine = line;
            int search = pos;
            while (search < src.length()) {
                int open = src.indexOf("<?", search);
                if (open < 0) {
                    search = src.length();
                    break;
                }
                if (regionMatchesIgnoreCase(open, "<?php")
                        && (open + 5 == src.length() || isPhpSpace(src.charAt(open + 5)))) {
                    search = open;
                    break;
                }
                if (src.startsWith("<?=", open)) {
                    search = open;
                    break;
                }
                search = open + 1;
            }
            if (search > start) {
                add(T_INLINE_HTML, src.substring(start, search), startLine);
            }
            if (search >= src.length()) {
                pos = src.length();
                return;
            }
            pos = search;
            int tagLine = line;
            if (src.startsWith("<?=", pos)) {
                add(T_OPEN_TAG_WITH_ECHO, "<?=", tagLine);
                pos += 3;
            } else {
                int end = pos + 5;
                if (end < src.length()) {
                    char c = src.charAt(end);
                    if (c == '\r' && end + 1 < src.length() && src.charAt(end + 1) == '\n') {
                        end += 2;
                    } else {
                        end++;
                    }
                }
                add(T_OPEN_TAG, src.substring(pos, end), tagLine);
                pos = end;
            }
            code = true;
        }

        private boolean isPhpSpace(char c) {
            return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == '\u000B';
        }

        private boolean regionMatchesIgnoreCase(int at, String text) {
            if (at + text.length() > src.length()) {
                return false;
            }
            for (int i = 0; i < text.length(); i++) {
                if (Character.toLowerCase(src.charAt(at + i)) != Character.toLowerCase(text.charAt(i))) {
                    return false;
                }
            }
            return true;
        }

        // ---------------------------------------------------------------- code

        private void lexCodeLoop() {
            while (pos < src.length() && code) {
                if (!lexCodeStep()) {
                    return;
                }
            }
        }

        /** Emits one code token; false when inline HTML resumes or the file ended. */
        private boolean lexCodeStep() {
            char c = src.charAt(pos);
            boolean propertyLookup = lookingForProperty;
            if (!isPhpSpace(c) && c != '#' && !src.startsWith("//", pos) && !src.startsWith("/*", pos)) {
                lookingForProperty = false;
            }
            if (isPhpSpace(c)) {
                int start = pos;
                int startLine = line;
                while (pos < src.length() && isPhpSpace(src.charAt(pos))) {
                    pos++;
                }
                add(T_WHITESPACE, src.substring(start, pos), startLine);
                return true;
            }
            if (src.startsWith("?>", pos)) {
                int start = pos;
                int startLine = line;
                pos += 2;
                if (pos < src.length() && src.charAt(pos) == '\n') {
                    pos++;
                } else if (pos + 1 < src.length() && src.charAt(pos) == '\r' && src.charAt(pos + 1) == '\n') {
                    pos += 2;
                }
                add(T_CLOSE_TAG, src.substring(start, pos), startLine);
                code = false;
                return false;
            }
            if (c == '#' && pos + 1 < src.length() && src.charAt(pos + 1) == '[') {
                add(T_ATTRIBUTE, "#[", line);
                pos += 2;
                return true;
            }
            if (c == '#' || src.startsWith("//", pos)) {
                int start = pos;
                int startLine = line;
                while (pos < src.length() && src.charAt(pos) != '\n' && src.charAt(pos) != '\r'
                        && !src.startsWith("?>", pos)) {
                    pos++;
                }
                add(T_COMMENT, src.substring(start, pos), startLine);
                return true;
            }
            if (src.startsWith("/*", pos)) {
                int start = pos;
                int startLine = line;
                int close = src.indexOf("*/", pos + 2);
                pos = close < 0 ? src.length() : close + 2;
                String text = src.substring(start, pos);
                boolean doc = text.startsWith("/**") && text.length() > 4
                        && text.charAt(3) != '/' && text.charAt(3) != '*' && text.charAt(3) != '#';
                add(doc ? T_DOC_COMMENT : T_COMMENT, text, startLine);
                return true;
            }
            if (c == '\'') {
                int start = pos;
                int startLine = line;
                pos++;
                while (pos < src.length()) {
                    char q = src.charAt(pos);
                    if (q == '\\' && pos + 1 < src.length()) {
                        pos += 2;
                        continue;
                    }
                    pos++;
                    if (q == '\'') {
                        break;
                    }
                }
                add(T_CONSTANT_ENCAPSED_STRING, src.substring(start, pos), startLine);
                return true;
            }
            if (c == '"') {
                lexDoubleQuoted();
                return true;
            }
            if (c == '`') {
                addString("`");
                pos++;
                lexEncapsed('`', null);
                return true;
            }
            if (c == '$') {
                if (pos + 1 < src.length() && isIdentStart(src.charAt(pos + 1))) {
                    lexVariable();
                    return true;
                }
                addString("$");
                pos++;
                return true;
            }
            if (isIdentStart(c) || c == '\\') {
                lexIdentifier(propertyLookup);
                return true;
            }
            if (Character.isDigit(c) || (c == '.' && pos + 1 < src.length() && Character.isDigit(src.charAt(pos + 1)))) {
                lexNumber();
                return true;
            }
            if (src.startsWith("<<<", pos)) {
                if (lexHeredoc()) {
                    return true;
                }
                add(T_SL, "<<", line);
                pos += 2;
                return true;
            }
            if (c == '(') {
                int castEnd = castEnd(pos);
                if (castEnd > 0) {
                    String text = src.substring(pos, castEnd);
                    add(castToken(text), text, line);
                    pos = castEnd;
                    return true;
                }
                addString("(");
                pos++;
                return true;
            }
            if (c == '&' && !src.startsWith("&&", pos) && !src.startsWith("&=", pos)) {
                int look = pos + 1;
                while (look < src.length() && isPhpSpace(src.charAt(look))) {
                    look++;
                }
                boolean followedByVar = look < src.length()
                        && (src.charAt(look) == '$' || src.startsWith("...", look));
                add(followedByVar ? T_AMPERSAND_FOLLOWED_BY_VAR_OR_VARARG
                        : T_AMPERSAND_NOT_FOLLOWED_BY_VAR_OR_VARARG, "&", line);
                pos++;
                return true;
            }
            for (Map.Entry<String, Integer> e : OPERATORS.entrySet()) {
                if (src.startsWith(e.getKey(), pos)) {
                    add(e.getValue(), e.getKey(), line);
                    pos += e.getKey().length();
                    if (e.getValue() == T_OBJECT_OPERATOR || e.getValue() == T_NULLSAFE_OBJECT_OPERATOR) {
                        lookingForProperty = true;
                    }
                    return true;
                }
            }
            addString(String.valueOf(c));
            pos++;
            if (haltPending && c == ';') {
                haltPending = false;
                if (pos < src.length()) {
                    add(T_INLINE_HTML, src.substring(pos), line);
                    pos = src.length();
                }
                return false;
            }
            return true;
        }

        private int castEnd(int at) {
            int i = at + 1;
            while (i < src.length() && (src.charAt(i) == ' ' || src.charAt(i) == '\t')) {
                i++;
            }
            int word = i;
            while (i < src.length() && Character.isLetter(src.charAt(i))) {
                i++;
            }
            String name = src.substring(word, i).toLowerCase();
            if (!name.equals("int") && !name.equals("integer") && !name.equals("bool")
                    && !name.equals("boolean") && !name.equals("float") && !name.equals("double")
                    && !name.equals("real") && !name.equals("string") && !name.equals("array")
                    && !name.equals("object") && !name.equals("unset")) {
                return -1;
            }
            while (i < src.length() && (src.charAt(i) == ' ' || src.charAt(i) == '\t')) {
                i++;
            }
            return i < src.length() && src.charAt(i) == ')' ? i + 1 : -1;
        }

        private int castToken(String text) {
            String name = text.replace("(", "").replace(")", "").trim().toLowerCase();
            return switch (name) {
                case "int", "integer" -> T_INT_CAST;
                case "bool", "boolean" -> T_BOOL_CAST;
                case "float", "double", "real" -> T_DOUBLE_CAST;
                case "string" -> T_STRING_CAST;
                case "array" -> T_ARRAY_CAST;
                case "object" -> T_OBJECT_CAST;
                default -> T_UNSET_CAST;
            };
        }

        private void lexNumber() {
            int start = pos;
            int startLine = line;
            boolean number = false;
            if (src.charAt(pos) == '.') {
                pos++;
                while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '_')) {
                    pos++;
                }
                exponent();
                number = true;
            } else if (src.startsWith("0x", pos) || src.startsWith("0X", pos)) {
                pos += 2;
                while (pos < src.length() && (Character.digit(src.charAt(pos), 16) >= 0 || src.charAt(pos) == '_')) {
                    pos++;
                }
            } else if (src.startsWith("0b", pos) || src.startsWith("0B", pos)) {
                pos += 2;
                while (pos < src.length() && (src.charAt(pos) == '0' || src.charAt(pos) == '1' || src.charAt(pos) == '_')) {
                    pos++;
                }
            } else if (src.startsWith("0o", pos) || src.startsWith("0O", pos)) {
                pos += 2;
                while (pos < src.length() && ((src.charAt(pos) >= '0' && src.charAt(pos) <= '7') || src.charAt(pos) == '_')) {
                    pos++;
                }
            } else {
                while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '_')) {
                    pos++;
                }
                if (pos < src.length() && src.charAt(pos) == '.') {
                    number = true;
                    pos++;
                    while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '_')) {
                        pos++;
                    }
                }
                int beforeExponent = pos;
                if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
                    exponent();
                    if (pos != beforeExponent) {
                        number = true;
                    }
                }
            }
            add(number ? T_DNUMBER : T_LNUMBER, src.substring(start, pos), startLine);
        }

        private void exponent() {
            int save = pos;
            if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
                int i = pos + 1;
                if (i < src.length() && (src.charAt(i) == '+' || src.charAt(i) == '-')) {
                    i++;
                }
                int digits = i;
                while (i < src.length() && (Character.isDigit(src.charAt(i)) || src.charAt(i) == '_')) {
                    i++;
                }
                if (i > digits) {
                    pos = i;
                    return;
                }
            }
            pos = save;
        }

        private void lexVariable() {
            int start = pos;
            int startLine = line;
            pos++;
            while (pos < src.length() && isIdentPart(src.charAt(pos))) {
                pos++;
            }
            add(T_VARIABLE, src.substring(start, pos), startLine);
        }

        private void lexIdentifier(boolean propertyLookup) {
            int start = pos;
            int startLine = line;
            if (src.charAt(pos) == '\\') {
                if (pos + 1 < src.length() && isIdentStart(src.charAt(pos + 1))) {
                    pos++;
                    consumeNameSegments();
                    add(T_NAME_FULLY_QUALIFIED, src.substring(start, pos), startLine);
                } else {
                    add(T_NS_SEPARATOR, "\\", startLine);
                    pos++;
                }
                return;
            }
            String word = readIdentifier();
            if (propertyLookup) {
                add(T_STRING, word, startLine);
                return;
            }
            String lower = word.toLowerCase();
            if (lower.equals("yield")) {
                int look = pos;
                while (look < src.length() && isPhpSpace(src.charAt(look))) {
                    look++;
                }
                if (look > pos && regionMatchesIgnoreCase(look, "from")
                        && (look + 4 == src.length() || !isIdentPart(src.charAt(look + 4)))) {
                    pos = look + 4;
                    add(T_YIELD_FROM, src.substring(start, pos), startLine);
                    return;
                }
            }
            if ((lower.equals("private") || lower.equals("protected") || lower.equals("public"))
                    && pos + 5 <= src.length() && regionMatchesIgnoreCase(pos, "(set)")) {
                int token = lower.equals("private") ? T_PRIVATE_SET
                        : lower.equals("protected") ? T_PROTECTED_SET : T_PUBLIC_SET;
                pos += 5;
                add(token, src.substring(start, pos), startLine);
                return;
            }
            if (lower.equals("namespace") && pos < src.length() && src.charAt(pos) == '\\'
                    && pos + 1 < src.length() && isIdentStart(src.charAt(pos + 1))) {
                pos++;
                consumeNameSegments();
                add(T_NAME_RELATIVE, src.substring(start, pos), startLine);
                return;
            }
            Integer keyword = KEYWORDS.get(lower);
            if (keyword != null) {
                add(keyword, word, startLine);
                if (keyword == T_HALT_COMPILER) {
                    haltPending = true;
                }
                return;
            }
            if (pos < src.length() && src.charAt(pos) == '\\'
                    && pos + 1 < src.length() && isIdentStart(src.charAt(pos + 1))) {
                pos++;
                consumeNameSegments();
                add(T_NAME_QUALIFIED, src.substring(start, pos), startLine);
                return;
            }
            add(T_STRING, word, startLine);
        }

        private String readIdentifier() {
            int start = pos;
            while (pos < src.length() && isIdentPart(src.charAt(pos))) {
                pos++;
            }
            return src.substring(start, pos);
        }

        private void consumeNameSegments() {
            while (pos < src.length() && isIdentPart(src.charAt(pos))) {
                pos++;
            }
            while (pos < src.length() && src.charAt(pos) == '\\'
                    && pos + 1 < src.length() && isIdentStart(src.charAt(pos + 1))) {
                pos++;
                while (pos < src.length() && isIdentPart(src.charAt(pos))) {
                    pos++;
                }
            }
        }

        // -------------------------------------------------------------- strings

        private void lexDoubleQuoted() {
            int start = pos;
            int startLine = line;
            int end = findQuoteEnd(pos + 1, '"');
            if (end > 0 && !hasInterpolation(src.substring(pos + 1, end))) {
                pos = end + 1;
                add(T_CONSTANT_ENCAPSED_STRING, src.substring(start, pos), startLine);
                return;
            }
            addString("\"");
            pos++;
            lexEncapsed('"', null);
        }

        private int findQuoteEnd(int from, char quote) {
            int i = from;
            while (i < src.length()) {
                char c = src.charAt(i);
                if (c == '\\' && i + 1 < src.length()) {
                    i += 2;
                    continue;
                }
                if (c == quote) {
                    return i;
                }
                i++;
            }
            return -1;
        }

        private boolean hasInterpolation(String text) {
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\\') {
                    i++;
                    continue;
                }
                if (c == '$' && i + 1 < text.length()
                        && (isIdentStart(text.charAt(i + 1)) || text.charAt(i + 1) == '{')) {
                    return true;
                }
                if (c == '{' && i + 1 < text.length() && text.charAt(i + 1) == '$') {
                    return true;
                }
            }
            return false;
        }

        /** Scans the body of a double-quoted string, backtick or heredoc. */
        private void lexEncapsed(char quote, String heredocLabel) {
            int start = pos;
            int startLine = line;
            while (pos < src.length()) {
                if (heredocLabel != null) {
                    int markerEnd = heredocEnd(pos, heredocLabel);
                    if (markerEnd > 0) {
                        flushEncapsed(start, startLine);
                        int markerStart = pos;
                        add(T_END_HEREDOC, src.substring(markerStart, markerEnd), line);
                        pos = markerEnd;
                        return;
                    }
                } else if (src.charAt(pos) == quote) {
                    flushEncapsed(start, startLine);
                    addString(String.valueOf(quote));
                    pos++;
                    return;
                }
                char c = src.charAt(pos);
                if (c == '\\' && pos + 1 < src.length()) {
                    pos += 2;
                    continue;
                }
                if (c == '$') {
                    if (pos + 1 < src.length() && src.charAt(pos + 1) == '{') {
                        flushEncapsed(start, startLine);
                        add(T_DOLLAR_OPEN_CURLY_BRACES, "${", line);
                        pos += 2;
                        if (pos < src.length() && isIdentStart(src.charAt(pos))) {
                            int save = pos;
                            String name = readIdentifier();
                            if (pos < src.length() && src.charAt(pos) == '}') {
                                add(T_STRING_VARNAME, name, line);
                                addString("}");
                                pos++;
                            } else {
                                pos = save;
                                lexInterpolationCode();
                            }
                        } else {
                            lexInterpolationCode();
                        }
                        start = pos;
                        startLine = line;
                        continue;
                    }
                    if (pos + 1 < src.length() && isIdentStart(src.charAt(pos + 1))) {
                        flushEncapsed(start, startLine);
                        lexEncapsedVariable();
                        start = pos;
                        startLine = line;
                        continue;
                    }
                }
                if (c == '{' && pos + 1 < src.length() && src.charAt(pos + 1) == '$') {
                    flushEncapsed(start, startLine);
                    add(T_CURLY_OPEN, "{", line);
                    pos++;
                    lexInterpolationCode();
                    start = pos;
                    startLine = line;
                    continue;
                }
                pos++;
            }
            flushEncapsed(start, startLine);
        }

        private void flushEncapsed(int start, int startLine) {
            if (pos > start) {
                add(T_ENCAPSED_AND_WHITESPACE, src.substring(start, pos), startLine);
            }
        }

        private void lexEncapsedVariable() {
            int start = pos;
            int startLine = line;
            pos++;
            while (pos < src.length() && isIdentPart(src.charAt(pos))) {
                pos++;
            }
            String name = src.substring(start, pos);
            add(T_VARIABLE, name, startLine);
            if (pos < src.length() && src.charAt(pos) == '[') {
                int save = pos;
                int bracketLine = line;
                pos++;
                if (pos < src.length() && src.charAt(pos) == '$'
                        && pos + 1 < src.length() && isIdentStart(src.charAt(pos + 1))) {
                    addString("[");
                    lexVariable();
                    addString("]");
                    pos++;
                } else if (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                    addString("[");
                    int numStart = pos;
                    while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_')) {
                        pos++;
                    }
                    add(T_NUM_STRING, src.substring(numStart, pos), bracketLine);
                    addString("]");
                    pos++;
                } else if (pos < src.length() && src.charAt(pos) == '-'
                        && pos + 1 < src.length() && Character.isDigit(src.charAt(pos + 1))) {
                    addString("[");
                    addString("-");
                    pos++;
                    int numStart = pos;
                    while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                        pos++;
                    }
                    add(T_NUM_STRING, src.substring(numStart, pos), bracketLine);
                    addString("]");
                    pos++;
                } else if (pos < src.length() && isIdentStart(src.charAt(pos))) {
                    addString("[");
                    String key = readIdentifier();
                    add(T_STRING, key, bracketLine);
                    addString("]");
                    pos++;
                } else {
                    pos = save;
                }
            }
            if (pos + 1 < src.length() && src.charAt(pos) == '-' && src.charAt(pos + 1) == '>'
                    && pos + 2 < src.length() && isIdentStart(src.charAt(pos + 2))) {
                add(T_OBJECT_OPERATOR, "->", line);
                pos += 2;
                String prop = readIdentifier();
                add(T_STRING, prop, line);
            }
        }

        /** Lexes code between an interpolated brace and its matching "}". */
        private void lexInterpolationCode() {
            int depth = 1;
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == '}') {
                    addString("}");
                    pos++;
                    depth--;
                    if (depth == 0) {
                        return;
                    }
                    continue;
                }
                if (c == '{') {
                    addString("{");
                    pos++;
                    depth++;
                    continue;
                }
                if (!lexCodeStep()) {
                    return;
                }
            }
        }

        // -------------------------------------------------------------- heredoc

        private boolean lexHeredoc() {
            int start = pos;
            int startLine = line;
            int i = pos + 3;
            while (i < src.length() && (src.charAt(i) == ' ' || src.charAt(i) == '\t')) {
                i++;
            }
            boolean nowdoc = false;
            String label;
            if (i < src.length() && (src.charAt(i) == '\'' || src.charAt(i) == '"')) {
                char q = src.charAt(i);
                nowdoc = q == '\'';
                int labelStart = ++i;
                while (i < src.length() && isIdentPart(src.charAt(i))) {
                    i++;
                }
                if (i == labelStart || i >= src.length() || src.charAt(i) != q) {
                    return false;
                }
                label = src.substring(labelStart, i);
                i++;
            } else {
                int labelStart = i;
                while (i < src.length() && isIdentPart(src.charAt(i))) {
                    i++;
                }
                if (i == labelStart) {
                    return false;
                }
                label = src.substring(labelStart, i);
            }
            if (i >= src.length() || (src.charAt(i) != '\n' && src.charAt(i) != '\r')) {
                return false;
            }
            if (src.charAt(i) == '\r' && i + 1 < src.length() && src.charAt(i + 1) == '\n') {
                i += 2;
            } else {
                i++;
            }
            add(T_START_HEREDOC, src.substring(start, i), startLine);
            pos = i;
            if (nowdoc) {
                int contentStart = pos;
                int contentLine = line;
                int scan = pos;
                int markerEnd = -1;
                while (scan <= src.length()) {
                    markerEnd = heredocEnd(scan, label);
                    if (markerEnd > 0) {
                        break;
                    }
                    int nl = src.indexOf('\n', scan);
                    if (nl < 0) {
                        markerEnd = -1;
                        break;
                    }
                    scan = nl + 1;
                }
                if (markerEnd < 0) {
                    if (pos < src.length()) {
                        add(T_ENCAPSED_AND_WHITESPACE, src.substring(pos), contentLine);
                        pos = src.length();
                    }
                    return true;
                }
                if (markerEndStart > contentStart) {
                    add(T_ENCAPSED_AND_WHITESPACE, src.substring(contentStart, markerEndStart), contentLine);
                }
                add(T_END_HEREDOC, src.substring(markerEndStart, markerEnd), line);
                pos = markerEnd;
                return true;
            }
            lexEncapsed((char) 0, label);
            return true;
        }

        private int markerEndStart;

        /** End offset of a heredoc closing marker at the line starting at {@code at}, or -1. */
        private int heredocEnd(int at, String label) {
            if (at > 0 && src.charAt(at - 1) != '\n' && !(at == 0)) {
                return -1;
            }
            int i = at;
            while (i < src.length() && (src.charAt(i) == ' ' || src.charAt(i) == '\t')) {
                i++;
            }
            if (!src.startsWith(label, i)) {
                return -1;
            }
            int end = i + label.length();
            if (end < src.length() && isIdentPart(src.charAt(end))) {
                return -1;
            }
            markerEndStart = at;
            return end;
        }

        // ---------------------------------------------------------------- output

        private void add(int id, String text, int startLine) {
            PhpArray token = new PhpArray();
            token.append((long) id);
            token.append(text);
            token.append((long) startLine);
            out.append(token);
            countLines(text);
            if (haltPending && text.equals(";")) {
                haltPending = false;
                if (pos < src.length()) {
                    add(T_INLINE_HTML, src.substring(pos), line);
                    pos = src.length();
                }
            }
        }

        private void addString(String text) {
            out.append(text);
        }

        private void countLines(String text) {
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') {
                    line++;
                }
            }
        }

        private boolean isIdentStart(char c) {
            return c == '_' || Character.isLetter(c) || c >= 0x80 && !Character.isLetter(c);
        }

        private boolean isIdentPart(char c) {
            return c == '_' || Character.isLetterOrDigit(c) || c >= 0x80;
        }
    }
}
