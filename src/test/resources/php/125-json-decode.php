<?php
// json_decode with $assoc = true (objects as arrays), errors and depth.
function d($json, $depth = 512) {
    $v = json_decode($json, true, $depth);
    $code = json_last_error();
    $msg = json_last_error_msg();
    echo json_encode($json), " => ";
    var_export($v);
    echo " | ", $code, " ", $msg, "\n";
}

d('1');
d('-0');
d('-0.0');
d('1.0');
d('1e2');
d('1E-2');
d('0.1');
d('12345678901234567890');
d('9223372036854775807');
var_dump(json_decode('-9223372036854775808', true)); // var_export prints PHP_INT_MIN specially
d('9223372036854775808');
d('"plain"');
d('"esc \" \\\\ \/ \b \f \n \r \t"');
d('"aé😀"');
var_dump(strlen(json_decode('"\u0000x"', true)), ord(json_decode('"\u0000x"', true)));
d('true');
d('false');
d('null');
d('[]');
d('{}');
d(' [ 1 , "two" , 3.5 , true , null ] ');
d('{"a":1,"b":{"c":[1,2,{"d":null}]}}');
d('{"a":1,"a":2}');
d('{"1":"x","01":"y","":"z","-5":"neg"}');
d('[[[]]]');
// errors
d('');
d(' ');
d('[1,2');
d('[1,]');
d('{"a"}');
d('{a:1}');
d('01');
d('1.');
d('.5');
d('TRUE');
d('nul');
d('"tab	here"');
d('"\x"');
d('"\ud83d"');
d('"\ude00"');
d('[1] x');
// depth
d('[1]', 1);
d('1', 1);
d('[1]', 2);
d('[[1]]', 2);
d('{"a":{"b":1}}', 3);
d('{"a":{"b":1}}', 2);
// bigint as string
var_dump(json_decode('[12345678901234567890, 5]', true, 512, JSON_BIGINT_AS_STRING));
// JSON_OBJECT_AS_ARRAY with null $assoc
var_dump(json_decode('{"k":"v"}', null, 512, JSON_OBJECT_AS_ARRAY));
// success resets the error
json_decode('[', true);
echo json_last_error(), "\n";
json_decode('[]', true);
echo json_last_error(), "\n";
// theme.json-like document
$theme = '{
    "version": 3,
    "settings": {
        "color": { "palette": [ { "slug": "base", "color": "#FFFFFF", "name": "Base" } ] },
        "layout": { "contentSize": "645px", "wideSize": "1340px" },
        "typography": { "fluid": true, "fontSizes": [ { "slug": "small", "size": "0.9rem" } ] }
    },
    "styles": { "spacing": { "padding": { "top": "0px" } } }
}';
$t = json_decode($theme, true);
var_dump($t['settings']['color']['palette'][0]['color'], $t['settings']['layout']['wideSize'], $t['settings']['typography']['fluid']);
echo json_encode($t), "\n";
