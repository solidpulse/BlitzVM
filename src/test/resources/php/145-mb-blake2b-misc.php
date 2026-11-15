<?php
// 1. BLAKE2b (sodium_crypto_generichash) compared via hex2bin of real PHP's digests
// (until byte strings land, binary strings are compared directly via hex2bin/base64_decode, as in 143-password-zlib)
var_dump(sodium_crypto_generichash("") === hex2bin("0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8"));
var_dump(sodium_crypto_generichash("hello world") === hex2bin("256c83b297114d201b30179f3f0ef0cace9783622da5974326b436178aeef610"));
var_dump(sodium_crypto_generichash("hello world", str_repeat("k", 32), 16) === hex2bin("125149eb6f4be12cc08901ccfbaacb1f"));
var_dump(sodium_crypto_generichash(str_repeat("abcd", 100), str_repeat("x", 64), 64) === hex2bin("67bc6563de93bb4d044676735c2b1a1456c1a420e72708df9635cfaf629f94f0d80e01de2be74bb277cc110bea5690dbb5630d1762577ab6fe8961dc3eca3942"));

// 2. mb_convert_encoding, mb_detect_encoding, mb_scrub
var_dump(mb_detect_encoding("plain ascii"));
var_dump(mb_detect_encoding("café", ["ASCII", "UTF-8"]));
$latin1 = mb_convert_encoding("café", "ISO-8859-1", "UTF-8");
var_dump($latin1 === hex2bin("636166e9"));
var_dump(mb_convert_encoding($latin1, "UTF-8", "ISO-8859-1"));
$arrConv = mb_convert_encoding(["k" => "über"], "ISO-8859-1", "UTF-8");
var_dump($arrConv["k"] === hex2bin("fc626572"));
var_dump(mb_scrub("valid utf8 café"));

// 3. normalizer_normalize / normalizer_is_normalized
$decomposed = "e\u{0301}";
var_dump(normalizer_is_normalized($decomposed));
$composed = normalizer_normalize($decomposed);
var_dump($composed === "é", normalizer_is_normalized($composed));

// 4. inet_pton / inet_ntop
$ip4 = inet_pton("192.168.1.42");
var_dump($ip4 === hex2bin("c0a8012a"), inet_ntop($ip4));
$ip6 = inet_pton("2001:db8::1");
var_dump($ip6 === hex2bin("20010db8000000000000000000000001"), inet_ntop($ip6));
var_dump(inet_pton("not-an-ip"), inet_ntop("bad"));

// 5. get_class_methods & class_alias
class OrigWidget {
    public function alpha() {}
    protected function beta() {}
    private function gamma() {}
    public function inspectSelf() {
        return get_class_methods($this);
    }
}
class SubWidget extends OrigWidget {
    public function delta() {}
}
var_dump(get_class_methods('OrigWidget'));
var_dump(get_class_methods(new SubWidget()));
var_dump((new OrigWidget())->inspectSelf());

var_dump(class_alias('OrigWidget', 'AliasedWidget'));
$aw = new AliasedWidget();
var_dump($aw instanceof OrigWidget, $aw instanceof AliasedWidget, get_class($aw));

// 6. get_html_translation_table, session_status, opcache_get_status, error_get_last, timezone_identifiers_list
$spec = get_html_translation_table(HTML_SPECIALCHARS, ENT_QUOTES);
var_dump($spec['<'], $spec['"'], $spec["'"], count($spec));
$ent = get_html_translation_table(HTML_ENTITIES, ENT_COMPAT);
var_dump($ent['&'], $ent['é'], $ent['©']);
var_dump(session_status() === PHP_SESSION_NONE, opcache_get_status(), error_get_last());
$tz = timezone_identifiers_list();
var_dump(in_array('UTC', $tz, true), in_array('Europe/London', $tz, true), in_array('Asia/Kolkata', $tz, true));

// 7. get_defined_constants(true)['user']
define('BATCH3_USER_CONST', 'val42');
$cats = get_defined_constants(true);
var_dump($cats['user']['BATCH3_USER_CONST'], isset($cats['Core']['PHP_EOL']));
