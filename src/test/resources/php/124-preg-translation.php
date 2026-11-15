<?php
// PCRE constructs that need translating to Java's regex dialect, and real WordPress patterns.
function m($label, $re, $s, $flags = 0) {
    $r = preg_match($re, $s, $m, $flags);
    echo $label, ": ", var_export($r, true), " ", json_encode($m), "\n";
}
function all($label, $re, $s) {
    $r = preg_match_all($re, $s, $m, PREG_SET_ORDER);
    echo $label, ": ", var_export($r, true), " ", json_encode($m), "\n";
}

// Named groups (Java names allow no underscores) and references to them.
m("underscore names", '/(?P<first_name>\w+)\s(?<last_name>\w+)\s(?\'mid_dle\'\w+)?/', 'Ada Lovelace x');
m("name backref", '/(?<q_1>[\'"])(.*?)(?P=q_1)/', 'say "hi"');
m("k braces", '/(?<a_b>x)\k{a_b}/', 'axx');
m("g name", '/(?<a_b>y)\g{a_b}/', 'yy');

// POSIX classes.
m("alpha", '/[[:alpha:]]+/', '123abc456');
m("digit/space", '/[[:digit:]]+[[:space:]]+[[:upper:]]/', 'x 12  Q');
m("negated posix", '/[[:^digit:]]+/', '12ab34');
m("word/punct/xdigit", '/[[:word:]]+[[:punct:]][[:xdigit:]]+/', 'a_b!ff');
m("alnum lower", '/^[[:alnum:]]+ [[:lower:]]+$/', 'A1 bc');

// Modifiers.
m("ungreedy U", '/<.+>/U', '<a><b>');
m("ungreedy U lazy flips", '/<.+?>/U', '<a><b>');
m("extended x", '/ (\d+)  # digits
                   \s* - \s*   # dash
                   (\d+) /x', '10 - 20');
m("x keeps class spaces", '/[ ]x/x', 'a x');
m("x escaped space", '/a\ b/x', 'a b');
m("inline i", '/a(?i)b/', 'aB');
m("inline i group", '/a(?i:b)c/', 'aBc');
m("inline -i", '/(?i)a(?-i)b/', 'Ab');
m("u case", '/É/iu', 'é');
m("u word", '/^\w+$/u', 'héllo');
m("no u word", '/^\w+$/', 'hello');
m("S X J ignored", '/a/SXJ', 'a');
m("n no auto capture", '/(a)(?<x>b)/n', 'ab');

// Escapes.
m("hex", '/\x41\x{42}\x43/', 'ABC');
m("octal", '/\101\0102/', 'AB2');
m("h", '/a\hb/', "a\tb");
m("N", '/a\N+/', "abc\nd");
m("R", '/a\Rb/', "a\r\nb");
m("Q E", '/\Q.*?\E!/', 'x.*?!');
m("Q E in class", '/[\Q]-\E]+/', 'a]]-b');
m("A z Z", '/\Aab\z/', 'ab');
m("Z before newline", '/ab\Z/', "ab\n");
m("comment group", '/a(?#this is ignored)b/', 'ab');
m("fail verb", '/a(*FAIL)|b/', 'ab');
m("utf verb", '/(*UTF8)^.$/', 'é');
m("unicode property", '/\p{Lu}\p{Ll}+/u', 'xÉcole');
m("property negated", '/\P{L}+/u', 'ab12cd');
m("script property", '/\p{Han}+/u', 'abc漢字def');
m("pL short", '/\pL+/u', '12ab');
m("p caret", '/\p{^L}+/u', 'ab12');
m("zs", '#\p{Zs}#siu', "a b");

// Classes Java reads differently.
m("leading bracket", '/[]a]+/', 'x]a]');
m("negated leading bracket", '/[^]a]+/', ']xyz');
m("literal open bracket", '/[[a]+/', 'x[a[');
m("ampersands", '/[a&&b]+/', 'x&&ab');
m("dash at class end", '/[\w.-]+/', 'a-b.c d');
m("dash at end", '/[a-]+/', 'a-a');
m("backspace in class", '/[\b]/', 'a' . chr(8) . 'b');
m("caret not first", '/[a^]+/', 'x^a');
m("dollar in class", '/[$]x/', 'a$x');

// Braces that are not quantifiers.
m("lone brace", '/a{/', 'a{');
m("brace word", '/{name}/', 'x{name}');
m("closing brace", '/}/', 'a}');
m("brace comma", '/x{1,}/', 'xxx');

// Conditionals and branch reset.
m("conditional group set", '/^(<)?\w+(?(1)>)$/', '<tag>');
m("conditional group unset", '/^(<)?\w+(?(1)>)$/', 'tag');
m("conditional mismatch", '/^(<)?\w+(?(1)>)$/', '<tag');
m("conditional else", '/&#(x)?0*(?(1)27|39);?/i', '&#x27;');
m("conditional else 2", '/&#(x)?0*(?(1)27|39);?/i', '&#39;');
m("conditional named", '/(?<open>\()?x(?(<open>)\))/', '(x)');
m("conditional lookahead", '/<(?(?=!--)!--.*?-->|[^>]*>)/', 'a <!-- c --> <b>');
m("conditional lookahead 2", '/<(?(?=!--)!--.*?-->|[^>]*>)/', 'a <b> <!-- c -->');
m("conditional lookbehind", '/\b(\d(?(?<=0)[\d\.,]+|[\d\.,]*))x(\d[\d\.,]*)\b/', 'size 1024x768 px');
m("branch reset", '/(?|var:preset\|duotone\|(\S+)|var\(--wp--preset--duotone--(\S+)\))/', 'var:preset|duotone|blue-red');
m("branch reset 2", '/(?|var:preset\|duotone\|(\S+)|var\(--wp--preset--duotone--(\S+)\))/', 'var(--wp--preset--duotone--dark)');
m("branch reset then group", '/(?|(a)|(b))(c)/', 'bc');

// WordPress: the block parser's tokenizer (possessive quantifier inside).
$block = '/<!--\s+(?P<closer>\/)?wp:(?P<namespace>[a-z][a-z0-9_-]*\/)?(?P<name>[a-z][a-z0-9_-]*)\s+(?P<attrs>{(?:(?:[^}]+|}+(?=})|(?!}\s+\/?-->).)*+)?}\s+)?(?P<void>\/)?-->/s';
m("block open", $block, 'x <!-- wp:paragraph {"align":"center"} --> y');
m("block void", $block, '<!-- wp:core/image {"id":5} /-->');
m("block closer", $block, '<!-- /wp:paragraph -->');
m("block no attrs", $block, '<!-- wp:separator /-->');

// WordPress: get_shortcode_regex() for [gallery] and [caption].
$tagregexp = preg_quote('gallery') . '|' . preg_quote('caption');
$shortcode = '\\[(\\[?)(' . $tagregexp . ')(?![\\w-])([^\\]\\/]*(?:\\/(?!\\])[^\\]\\/]*)*?)(?:(\\/)\\]|\\](?:([^\\[]*+(?:\\[(?!\\/\\2\\])[^\\[]*+)*+)\\[\\/\\2\\])?)(\\]?)';
all("shortcodes", "/$shortcode/", 'a [gallery ids="1,2"] b [caption id="c1"]Text[/caption] c [[gallery]] d [gallery/]');

// WordPress: shortcode attribute parsing.
$atts = '/([\w-]+)\s*=\s*"([^"]*)"(?:\s|$)|([\w-]+)\s*=\s*\'([^\']*)\'(?:\s|$)|([\w-]+)\s*=\s*([^\s\'"]+)(?:\s|$)|"([^"]*)"(?:\s|$)|\'([^\']*)\'(?:\s|$)|(\S+)(?:\s|$)/';
all("shortcode atts", $atts, 'ids="1,2" size=\'large\' link=file "pos" flag');

// WordPress: _get_wptexturize_split_regex() with and without shortcodes.
$comment = '!(?:-(?!->)[^\-]*+)*+(?:-->)?';
$html = '<(?(?=!--)' . $comment . '|[^>]*>?)';
var_export(preg_split("/($html)/", 'a <b>x</b> <!-- c - d --> e', -1, PREG_SPLIT_DELIM_CAPTURE | PREG_SPLIT_NO_EMPTY));
echo "\n";
$tex = '\[[\/\[]?(?:gallery)(?=[\s\]\/])(?:[^\[\]<>]+|<[^\[\]>]*>)*+\]\]?';
var_export(preg_split("/($html|$tex)/", 'x [gallery ids="1"] <i>y</i>', -1, PREG_SPLIT_DELIM_CAPTURE | PREG_SPLIT_NO_EMPTY));
echo "\n";

// WordPress: kses-style attribute scan.
all("kses attrs", '/([_a-zA-Z][-_a-zA-Z0-9:.]*)\s*=\s*(?:"([^"]*)"|\'([^\']*)\'|([^\s"\'>]+))/', '<a href="/x" target=\'_blank\' rel=nofollow>');
m("media tag backref", '#<(?P<tag>video|audio)[^<]*?(?:>[\s\S]*?<\/(?P=tag)>|\s*\/>)#', 'x <video src="a.mp4"></video>');
