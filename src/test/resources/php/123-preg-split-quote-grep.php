<?php
// preg_split flags and limits, preg_quote, preg_grep, preg_last_error.
var_export(preg_split('/[\s,]+/', "a, b  c,d"));
echo "\n";
var_export(preg_split('//', 'abc'));
echo "\n";
var_export(preg_split('//', 'abc', -1, PREG_SPLIT_NO_EMPTY));
echo "\n";
var_export(preg_split('/x*/', 'axb'));
echo "\n";
var_export(preg_split('/(-)|(,)/', 'a-b,c', -1, PREG_SPLIT_DELIM_CAPTURE));
echo "\n";
var_export(preg_split('/(-)|(,)/', 'a-b,c', -1, PREG_SPLIT_DELIM_CAPTURE | PREG_SPLIT_NO_EMPTY));
echo "\n";
var_export(preg_split('/\d/', 'a1b2c3', 2));
echo "\n";
var_export(preg_split('/\d/', 'a1b2c3', 1));
echo "\n";
var_export(preg_split('/\d/', 'a1b2c3', 0));
echo "\n";
var_export(preg_split('/\d/', 'a1b2c3', -1, PREG_SPLIT_NO_EMPTY));
echo "\n";
var_export(preg_split('/ /', 'a  b', -1, PREG_SPLIT_NO_EMPTY | PREG_SPLIT_OFFSET_CAPTURE));
echo "\n";
var_export(preg_split('/,/', ',a,,b,', 3, PREG_SPLIT_NO_EMPTY));
echo "\n";
var_export(preg_split('/(\d)/', 'x1y', -1, PREG_SPLIT_DELIM_CAPTURE | PREG_SPLIT_OFFSET_CAPTURE));
echo "\n";
var_export(preg_split('/é/u', 'aébéc', -1, PREG_SPLIT_OFFSET_CAPTURE));
echo "\n";
var_export(preg_split('/\R/', "one\r\ntwo\nthree"));
echo "\n";
var_export(preg_split('/z/', ''));
echo "\n";
var_export(preg_split('/z/', '', -1, PREG_SPLIT_NO_EMPTY));
echo "\n";
// html split like wptexturize: keep tags as delimiters
var_export(preg_split('/(<[^>]*>)/', 'a <b>bold</b> c', -1, PREG_SPLIT_DELIM_CAPTURE | PREG_SPLIT_NO_EMPTY));
echo "\n";

var_dump(preg_quote('Hello.World?(1+1=2)[a-z]{3}^$|\\/#:!<>'));
var_dump(preg_quote('a/b#c', '/'));
var_dump(preg_quote('a/b#c', '#'));
var_dump(preg_quote("nul\0byte"));
var_dump(preg_quote(''));
var_dump(preg_match('/' . preg_quote('1.5*2', '/') . '/', 'is 1.5*2?'));

var_export(preg_grep('/^\d+$/', ['1', 'a', 'x' => '22', 'b3', 4]));
echo "\n";
var_export(preg_grep('/^\d+$/', ['1', 'a', 'x' => '22', 'b3', 4], PREG_GREP_INVERT));
echo "\n";
var_dump(preg_last_error(), preg_last_error_msg());
