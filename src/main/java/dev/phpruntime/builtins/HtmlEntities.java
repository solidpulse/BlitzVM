package dev.phpruntime.builtins;

import dev.phpruntime.runtime.PhpStrings;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Decoding of HTML entities for html_entity_decode and htmlspecialchars_decode. */
final class HtmlEntities {

    static final long ENT_HTML_QUOTE_SINGLE = 1;
    static final long ENT_HTML_QUOTE_DOUBLE = 2;
    static final long ENT_QUOTES = 3;
    static final long ENT_SUBSTITUTE = 8;
    static final long ENT_HTML401 = 0;
    static final long ENT_HTML5 = 48;
    /** PHP 8.1+ default for the flags argument. */
    static final long DEFAULT_FLAGS = ENT_QUOTES | ENT_SUBSTITUTE | ENT_HTML401;

    /** HTML 4.01 named entities, exported from PHP's get_html_translation_table(HTML_ENTITIES). */
    private static final String TABLE =
            "quot:34 amp:38 lt:60 gt:62 nbsp:160 iexcl:161 cent:162 pound:163 curren:164 yen:165 brvbar:166 " +
            "sect:167 uml:168 copy:169 ordf:170 laquo:171 not:172 shy:173 reg:174 macr:175 deg:176 " +
            "plusmn:177 sup2:178 sup3:179 acute:180 micro:181 para:182 middot:183 cedil:184 sup1:185 " +
            "ordm:186 raquo:187 frac14:188 frac12:189 frac34:190 iquest:191 Agrave:192 Aacute:193 Acirc:194 " +
            "Atilde:195 Auml:196 Aring:197 AElig:198 Ccedil:199 Egrave:200 Eacute:201 Ecirc:202 Euml:203 " +
            "Igrave:204 Iacute:205 Icirc:206 Iuml:207 ETH:208 Ntilde:209 Ograve:210 Oacute:211 Ocirc:212 " +
            "Otilde:213 Ouml:214 times:215 Oslash:216 Ugrave:217 Uacute:218 Ucirc:219 Uuml:220 Yacute:221 " +
            "THORN:222 szlig:223 agrave:224 aacute:225 acirc:226 atilde:227 auml:228 aring:229 aelig:230 " +
            "ccedil:231 egrave:232 eacute:233 ecirc:234 euml:235 igrave:236 iacute:237 icirc:238 iuml:239 " +
            "eth:240 ntilde:241 ograve:242 oacute:243 ocirc:244 otilde:245 ouml:246 divide:247 oslash:248 " +
            "ugrave:249 uacute:250 ucirc:251 uuml:252 yacute:253 thorn:254 yuml:255 OElig:338 oelig:339 " +
            "Scaron:352 scaron:353 Yuml:376 fnof:402 circ:710 tilde:732 Alpha:913 Beta:914 Gamma:915 " +
            "Delta:916 Epsilon:917 Zeta:918 Eta:919 Theta:920 Iota:921 Kappa:922 Lambda:923 Mu:924 Nu:925 " +
            "Xi:926 Omicron:927 Pi:928 Rho:929 Sigma:931 Tau:932 Upsilon:933 Phi:934 Chi:935 Psi:936 " +
            "Omega:937 alpha:945 beta:946 gamma:947 delta:948 epsilon:949 zeta:950 eta:951 theta:952 " +
            "iota:953 kappa:954 lambda:955 mu:956 nu:957 xi:958 omicron:959 pi:960 rho:961 sigmaf:962 " +
            "sigma:963 tau:964 upsilon:965 phi:966 chi:967 psi:968 omega:969 thetasym:977 upsih:978 piv:982 " +
            "ensp:8194 emsp:8195 thinsp:8201 zwnj:8204 zwj:8205 lrm:8206 rlm:8207 ndash:8211 mdash:8212 " +
            "lsquo:8216 rsquo:8217 sbquo:8218 ldquo:8220 rdquo:8221 bdquo:8222 dagger:8224 Dagger:8225 " +
            "bull:8226 hellip:8230 permil:8240 prime:8242 Prime:8243 lsaquo:8249 rsaquo:8250 oline:8254 " +
            "frasl:8260 euro:8364 image:8465 weierp:8472 real:8476 trade:8482 alefsym:8501 larr:8592 " +
            "uarr:8593 rarr:8594 darr:8595 harr:8596 crarr:8629 lArr:8656 uArr:8657 rArr:8658 dArr:8659 " +
            "hArr:8660 forall:8704 part:8706 exist:8707 empty:8709 nabla:8711 isin:8712 notin:8713 ni:8715 " +
            "prod:8719 sum:8721 minus:8722 lowast:8727 radic:8730 prop:8733 infin:8734 ang:8736 and:8743 " +
            "or:8744 cap:8745 cup:8746 int:8747 there4:8756 sim:8764 cong:8773 asymp:8776 ne:8800 equiv:8801 " +
            "le:8804 ge:8805 sub:8834 sup:8835 nsub:8836 sube:8838 supe:8839 oplus:8853 otimes:8855 " +
            "perp:8869 sdot:8901 lceil:8968 rceil:8969 lfloor:8970 rfloor:8971 lang:9001 rang:9002 loz:9674 " +
            "spades:9824 clubs:9827 hearts:9829 diams:9830";

    private static final Map<String, Integer> NAMED = new HashMap<>();
    private static final Map<Integer, String> ENCODE = new HashMap<>();

    static {
        for (String pair : TABLE.split(" ")) {
            int colon = pair.indexOf(':');
            String name = pair.substring(0, colon);
            int cp = Integer.parseInt(pair.substring(colon + 1));
            NAMED.put(name, cp);
            ENCODE.put(cp, name);
        }
    }

    private HtmlEntities() {
    }

    static String name(int cp) {
        return ENCODE.get(cp);
    }

    static Charset charset(String name) {
        if (name.length() == 5 && name.equalsIgnoreCase("UTF-8")) {
            return StandardCharsets.UTF_8;
        }
        MbConvertFunctions.Encoding e = MbConvertFunctions.encoding(name);
        return e == null || e.charset() == null ? StandardCharsets.UTF_8 : e.charset();
    }

    static String encode(String php, long flags, String encoding, boolean doubleEncode, boolean all) {
        Charset cs = charset(encoding);
        boolean ascii = cs.equals(StandardCharsets.UTF_8) && PhpStrings.isAscii(php);
        String text;
        if (ascii) {
            // ASCII is valid UTF-8 and needs no decoder; php returns the same string when it
            // has nothing to escape.
            text = php;
        } else {
            boolean valid = PhpStrings.isEncoding(php, cs);
            if (!valid && (flags & (4 | ENT_SUBSTITUTE)) == 0) {
                return "";
            }
            text = cs.equals(StandardCharsets.UTF_8)
                    ? PhpStrings.decodeUtf8Replacing(php, (flags & 4) != 0)
                    : PhpStrings.decode(php, cs, (flags & 4) != 0 ? "" : "\uFFFD");
        }
        if (ascii && nothingToEncode(text, flags)) {
            return php;
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            if (cp == '&' && !doubleEncode) {
                int semi = text.indexOf(';', i + 1);
                if (semi > i && semi - i < 40 && knownEntity(text.substring(i + 1, semi), flags)) {
                    out.append(text, i, semi + 1);
                    i = semi + 1;
                    continue;
                }
            }
            if (cp == '&') {
                out.append("&amp;");
            } else if (cp == '<') {
                out.append("&lt;");
            } else if (cp == '>') {
                out.append("&gt;");
            } else if (cp == '"' && (flags & 2) != 0) {
                out.append("&quot;");
            } else if (cp == '\'' && (flags & 1) != 0) {
                out.append((flags & 48) == ENT_HTML401 ? "&#039;" : "&apos;");
            } else if ((flags & 128) != 0 && !allowed(cp, flags & 48)) {
                out.append('\uFFFD');
            } else {
                String name = all ? ENCODE.get(cp) : null;
                if (name != null && cp != '"') {
                    out.append('&').append(name).append(';');
                } else {
                    out.appendCodePoint(cp);
                }
            }
            i += Character.charCount(cp);
        }
        return PhpStrings.encode(out.toString(), cs, "?");
    }

    private static boolean knownEntity(String body, long flags) {
        if (body.startsWith("#")) {
            int cp = numeric(body);
            return cp >= 0 && allowed(cp, flags & 48);
        }
        return NAMED.containsKey(body) || body.equals("apos") && (flags & 48) != ENT_HTML401;
    }

    /** The HTML 4.01 named entities as (name, code point) pairs, in PHP's table order. */
    static java.util.List<Map.Entry<String, Integer>> namedTable() {
        java.util.List<Map.Entry<String, Integer>> out = new java.util.ArrayList<>();
        for (String pair : TABLE.split(" ")) {
            int colon = pair.indexOf(':');
            out.add(Map.entry(pair.substring(0, colon), Integer.parseInt(pair.substring(colon + 1))));
        }
        return out;
    }

    /** html_entity_decode: all named HTML 4.01 entities plus numeric ones. */
    static String decodeAll(String s, long flags) {
        return decode(s, flags, true, StandardCharsets.UTF_8);
    }

    static String decodeAll(String s, long flags, String encoding) {
        return decode(s, flags, true, charset(encoding));
    }

    /** htmlspecialchars_decode: only &amp; &lt; &gt; and the quote entities. */
    static String decodeSpecial(String s, long flags) {
        return decode(s, flags, false, StandardCharsets.UTF_8);
    }

    private static String decode(String s, long flags, boolean all, Charset charset) {
        if (s.indexOf('&') < 0) {
            return s;
        }
        long doctype = flags & 48;
        StringBuilder sb = new StringBuilder(s.length());
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            int semi = c == '&' ? s.indexOf(';', i + 1) : -1;
            if (semi < 0 || semi - i > 40) {
                sb.append(c);
                i++;
                continue;
            }
            String body = s.substring(i + 1, semi);
            int cp;
            if (body.startsWith("#")) {
                cp = numeric(body);
                if (cp >= 0 && ((!all && !isSpecial(cp)) || !allowed(cp, doctype))) {
                    cp = -1;
                }
            } else if (body.equals("apos")) {
                cp = doctype != ENT_HTML401 ? '\'' : -1;
            } else if (all) {
                Integer named = NAMED.get(body);
                cp = named == null ? -1 : named;
            } else {
                cp = switch (body) {
                    case "amp" -> '&';
                    case "lt" -> '<';
                    case "gt" -> '>';
                    case "quot" -> '"';
                    default -> -1;
                };
            }
            if ((cp == '\'' && (flags & ENT_HTML_QUOTE_SINGLE) == 0) || (cp == '"' && (flags & ENT_HTML_QUOTE_DOUBLE) == 0)) {
                cp = -1;
            }
            if (cp < 0) {
                sb.append(c);
                i++;
                continue;
            }
            String text = new String(Character.toChars(cp));
            if (!PhpStrings.canEncode(text, charset)) {
                sb.append(s, i, semi + 1);
            } else {
                sb.append(PhpStrings.encode(text, charset, "?"));
            }
            i = semi + 1;
        }
        return sb.toString();
    }

    private static boolean isSpecial(int cp) {
        return cp == '&' || cp == '<' || cp == '>' || cp == '"' || cp == '\'';
    }

    private static int numeric(String body) {
        boolean hex = body.length() > 1 && (body.charAt(1) == 'x' || body.charAt(1) == 'X');
        String digits = body.substring(hex ? 2 : 1);
        if (digits.isEmpty() || digits.length() > 8) {
            return -1;
        }
        int cp = 0;
        for (int k = 0; k < digits.length(); k++) {
            int d = Character.digit(digits.charAt(k), hex ? 16 : 10);
            if (d < 0) {
                return -1;
            }
            cp = cp * (hex ? 16 : 10) + d;
        }
        return cp <= 0x10FFFF ? cp : -1;
    }

    /** True when the escaping loop would append {@code text} unchanged. */
    private static boolean nothingToEncode(String text, long flags) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '&' || c == '<' || c == '>' || c == '"' || c == '\'' || c < 0x20 || c == 0x7F) {
                return false;
            }
        }
        return true;
    }

    /** Which numeric references PHP decodes for the document type (unicode_cp_is_allowed). */
    private static boolean allowed(int cp, long doctype) {
        boolean nonChar = (cp & 0xFFFF) >= 0xFFFE || (cp >= 0xFDD0 && cp <= 0xFDEF);
        if (doctype == ENT_HTML401) {
            return (cp >= 0x20 && cp <= 0x7E) || cp == 0x0A || cp == 0x09 || cp == 0x0D
                    || (cp >= 0xA0 && cp <= 0xD7FF) || (cp >= 0xE000 && cp <= 0x10FFFF && !nonChar);
        }
        if (doctype == ENT_HTML5) {
            return (cp >= 0x20 && cp <= 0x7E) || (cp >= 0x09 && cp <= 0x0D && cp != 0x0B)
                    || (cp >= 0xA0 && cp <= 0xD7FF) || (cp >= 0xE000 && cp <= 0x10FFFF && !nonChar);
        }
        // XHTML and XML 1.0
        return (cp >= 0x20 && cp <= 0xD7FF) || cp == 0x0A || cp == 0x09 || cp == 0x0D
                || (cp >= 0xE000 && cp <= 0x10FFFF && cp != 0xFFFE && cp != 0xFFFF);
    }
}
