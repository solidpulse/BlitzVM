<?php
$samples = ["", "123", "12.5", "abc", "ABC", "aBc", "abc123", " \t\n", "a b", "ff09AF", "fg", "!@#", "!a", "é", chr(127), "Hello World!", "0"];
foreach (["ctype_digit", "ctype_alpha", "ctype_alnum", "ctype_upper", "ctype_lower", "ctype_space", "ctype_xdigit", "ctype_punct", "ctype_print", "ctype_graph", "ctype_cntrl"] as $fn) {
    echo str_pad($fn, 13);
    foreach ($samples as $s) {
        echo ct($fn, $s) ? "1" : "0";
    }
    echo "\n";
}
// no variable function calls yet, so dispatch by hand
function ct($fn, $s) {
    switch ($fn) {
        case "ctype_digit": return ctype_digit($s);
        case "ctype_alpha": return ctype_alpha($s);
        case "ctype_alnum": return ctype_alnum($s);
        case "ctype_upper": return ctype_upper($s);
        case "ctype_lower": return ctype_lower($s);
        case "ctype_space": return ctype_space($s);
        case "ctype_xdigit": return ctype_xdigit($s);
        case "ctype_punct": return ctype_punct($s);
        case "ctype_print": return ctype_print($s);
        case "ctype_graph": return ctype_graph($s);
        case "ctype_cntrl": return ctype_cntrl($s);
    }
}
var_dump(ctype_digit(null), ctype_alpha(true), ctype_digit([]));

var_dump(mb_internal_encoding(), mb_internal_encoding("UTF-8"), mb_internal_encoding());
var_dump(mb_check_encoding("héllo"), mb_check_encoding(["a" => "é", "b" => ["ü"]]), mb_check_encoding("plain", "UTF-8"));
var_dump(mb_ord("A"), mb_ord("é"), mb_ord("€uro"), mb_ord("😀"));
var_dump(mb_chr(65), mb_chr(233), mb_chr(0x1F600), mb_chr(0xD800), mb_chr(0x110000), mb_chr(8364) === "€");
var_dump(mb_substitute_character(), mb_substitute_character("none"), mb_detect_order(), mb_detect_order(["UTF-8", "ASCII"]));
