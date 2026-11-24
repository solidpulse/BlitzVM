<?php
// Every builtin from the named-call benchmark with its parameters named, in different
// orders and mixed with positional arguments.
$words = ['abc ', 'Def', 'abX', 'zz', 'Hello', 'ab', 'q', 'World'];
$s = 'Hello';
$html = '<a href="x">';

echo htmlentities(string: $s), "\n";
echo htmlentities($s, flags: ENT_QUOTES), "\n";
echo htmlentities($s, ENT_QUOTES, encoding: 'UTF-8'), "\n";
echo htmlentities($s, ENT_QUOTES, 'UTF-8', double_encode: false), "\n";
echo htmlentities(flags: ENT_QUOTES, string: $html), "\n";
echo htmlentities(double_encode: false, flags: ENT_QUOTES, string: $html), "\n";

echo str_pad(string: 'ab', length: 6), "\n";
echo str_pad('ab', length: 6, pad_string: '-'), "\n";
echo str_pad(string: 'ab', pad_type: STR_PAD_LEFT, length: 5, pad_string: '-'), "\n";
echo str_pad(pad_string: '-', pad_type: STR_PAD_BOTH, string: 'ab', length: 7), "\n";

var_dump(base64_decode(string: 'YWJj', strict: true));
var_dump(base64_decode(strict: false, string: 'YWJj'));

var_dump(json_encode(value: ['a' => 1.5, 'b' => 'x/y'], flags: JSON_UNESCAPED_SLASHES));
var_dump(json_encode(['a' => 1], depth: 4));
var_dump(json_decode(json: '{"a":1}', associative: true));
var_dump(json_decode(json: '{"a":1}', depth: 8, flags: JSON_OBJECT_AS_ARRAY));

var_dump(array_slice(array: $words, offset: 2, length: 3));
var_dump(array_slice($words, 1, 2, preserve_keys: true));
var_dump(array_slice(array: $words, length: 2, offset: -2));

var_dump(in_array(needle: 'zz', haystack: $words));
var_dump(in_array($words[1], $words, strict: true));
var_dump(in_array(strict: false, haystack: ['1', 2], needle: 1));

var_dump(class_exists(class: 'stdClass', autoload: false));
var_dump(class_exists(class: 'ArrayObject'));

var_dump(explode(separator: 'l', string: $s));
var_dump(explode('l', $s, limit: 2));
var_dump(explode(limit: -1, separator: 'l', string: $s));

echo implode(separator: '-', array: $words), "\n";
echo implode(array: ['a', 'b', 'c'], separator: ','), "\n";

echo mb_substr(string: $s, start: 1), "\n";
echo mb_substr(string: $s, start: 1, length: 3, encoding: 'UTF-8'), "\n";
echo mb_substr(encoding: 'UTF-8', start: 2, string: $s), "\n";

var_dump(array_filter(array: $words, callback: fn($k) => $k > 4, mode: ARRAY_FILTER_USE_KEY));
var_dump(array_filter(array: [0, 1, '', 'x', null]));
var_dump(array_filter(array: ['a' => 1, 'b' => 2], callback: fn($v, $k) => $k === 'b', mode: ARRAY_FILTER_USE_BOTH));

var_dump(preg_match(pattern: '/l/', subject: $s));
var_dump(preg_match('/l/', $s, flags: PREG_OFFSET_CAPTURE));
var_dump(preg_match(pattern: '/L/i', subject: $s, flags: 0, offset: 1));

var_dump(number_format(num: 1234.5, decimals: 2));
var_dump(number_format(1234.5, decimals: 1, decimal_separator: ',', thousands_separator: '.'));
var_dump(number_format(num: 1234.5, thousands_separator: '_', decimals: 0, decimal_separator: ','));

var_dump(array_keys(array: $words, filter_value: 'zz'));
var_dump(array_keys(array: $words, filter_value: 'ab', strict: true));
var_dump(array_keys(array: $words, filter_value: 'zz', strict: false));

var_dump(str_replace(search: 'l', replace: 'L', subject: $s));
var_dump(str_replace('a', 'b', subject: $words[0]));

var_dump(round(num: 1.2345, precision: 2));
var_dump(round(1.2345, precision: 3));
