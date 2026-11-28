<?php
// Named arguments that skip optional parameters whose defaults are not plain constants:
// the skipped slot gets the builtin's own "not passed" value (null), exactly as php does.
$words = ['abc', 'def', 'ghi', 'jkl'];
$s = 'Hello';

echo mb_substr(string: $s, start: 1, encoding: 'UTF-8'), "\n";
echo mb_substr(string: $s, start: 1, length: null, encoding: 'UTF-8'), "\n";
echo htmlentities(string: '<a>', flags: ENT_QUOTES), "\n";
echo htmlentities(string: '<a>', flags: ENT_QUOTES, encoding: 'UTF-8'), "\n";

var_dump(json_decode(json: '{"a":1}', depth: 2, flags: JSON_OBJECT_AS_ARRAY));
var_dump(json_decode(json: '{"a":1}', flags: JSON_OBJECT_AS_ARRAY));
var_dump(json_decode(json: '{"a":1}', depth: 2));

var_dump(array_slice($words, offset: 1, preserve_keys: true));
var_dump(array_slice($words, offset: 1, length: 2));
var_dump(array_slice(array: $words, preserve_keys: true, offset: -2));

var_dump(explode(separator: ',', string: 'a,b,c'));
var_dump(explode(string: 'a,b,c', separator: ','));
var_dump(in_array(needle: '1', haystack: [1, 2]));
var_dump(in_array(needle: 1, haystack: [1, 2], strict: true));

var_dump(class_exists(class: 'stdClass'));
var_dump(class_exists(class: 'NotAClassName'));
var_dump(str_pad(string: 'x', length: 3));
var_dump(str_pad(length: 3, string: 'x'));

var_dump(preg_match(pattern: '/l/', subject: $s));
var_dump(preg_match(pattern: '/l/', subject: $s, flags: PREG_OFFSET_CAPTURE));
var_dump(number_format(num: 1234.5));
var_dump(round(num: 1.2345));
var_dump(base64_decode(string: 'YWJj'));
var_dump(str_replace(search: 'l', replace: 'L', subject: $s));
var_dump(array_keys(array: $words, filter_value: 'def'));

var_dump(json_encode(value: ['a' => [1, 2]], depth: 4));
var_dump(json_encode(value: ['a' => [1, 2]], flags: JSON_UNESCAPED_SLASHES));
