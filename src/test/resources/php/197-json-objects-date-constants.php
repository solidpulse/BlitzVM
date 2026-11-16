<?php
// json_decode without $assoc returns stdClass objects (WordPress decodes theme-i18n.json so).
$s = json_decode('{"settings":{"typography":{"fontSizes":[{"name":"Font size name"}]}}}');
var_dump(is_array($s->settings->typography->fontSizes), $s->settings->typography->fontSizes[0]->name);
print_r(json_decode('{"0":1,"":2,"a b":{}}')); print_r(json_decode('[{"x":1}]', true));
print_r(json_decode('{"x":{"y":1}}', false, 512, JSON_OBJECT_AS_ARRAY)); print_r(json_decode('{"x":1}', null, 512, JSON_OBJECT_AS_ARRAY));
$o = json_decode('{"k":[1,{"z":true}]}');
var_dump($o->k[1]->z, json_encode($o)); print_r((array) $o);
// The global DATE_* format constants.
foreach (['DATE_ATOM', 'DATE_COOKIE', 'DATE_RFC2822', 'DATE_RFC7231', 'DATE_RSS', 'DATE_W3C', 'DATE_RFC3339_EXTENDED'] as $c) {
    echo $c, ' ', constant($c), ' ', gmdate(constant($c), 1700000000), "\n";
}
