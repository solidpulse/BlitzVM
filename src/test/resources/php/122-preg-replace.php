<?php
// preg_replace: references, escapes, limits, counts, array forms.
var_dump(preg_replace('/(\w+) (\w+)/', '$2 $1', 'hello world'));
var_dump(preg_replace('/(\w+) (\w+)/', '\2 \1', 'hello world'));
var_dump(preg_replace('/(\w+) (\w+)/', '${2}1 $11', 'hello world'));
var_dump(preg_replace('/(a)(b)?/', '[$2|$1]', 'a ab'));
var_dump(preg_replace('/x/', '\\\\', 'axb'));
var_dump(preg_replace('/x/', '\$1', 'axb'));
var_dump(preg_replace('/x/', '$', 'axb'));
var_dump(preg_replace('/x/', '${1', 'axb'));
var_dump(preg_replace('/x/', '\\', 'axb'));
var_dump(preg_replace('/(x)/', '$0$0', 'axb'));
var_dump(preg_replace('/(x)/', '$9', 'axb'));
var_dump(preg_replace('/a/', 'b', 'aaaa', 2));
var_dump(preg_replace('/a/', 'b', 'aaaa', -1, $count), $count);
var_dump(preg_replace('/z/', 'b', 'aaaa', -1, $count), $count);
var_dump(preg_replace('/x*/', '-', 'abc'));
var_dump(preg_replace('/^/m', '> ', "one\ntwo"));
var_dump(preg_replace(['/a/', '/b/'], ['b', 'c'], 'ab'));
var_dump(preg_replace(['/a/', '/b/', '/c/'], ['1'], 'abc'));
var_dump(preg_replace(['/a/', '/b/'], 'X', 'abc'));
var_dump(preg_replace('/\d/', '#', ['x' => 'a1', 'y' => 'b22', 3 => 'none'], -1, $count), $count);
var_dump(preg_replace(['/\s+/', '/^\s|\s$/'], [' ', ''], "  lots   of\t\tspace  "));
var_dump(preg_replace('/(?<word>\w+)/', '<$1>', 'named groups'));
var_dump(preg_replace('/é/u', 'e', 'café crème'));
var_dump(preg_replace('/[^a-z0-9]+/i', '-', 'Hello, World! 2026'));
// WordPress-style: wpautop-ish and sanitize_title-ish.
var_dump(preg_replace('|<br\s*/?>\s*<br\s*/?>|', "\n\n", "a<br><br />b"));
var_dump(preg_replace('/&.+?;/', '', 'a&amp;b&#8217;c'));
var_dump(strtolower(preg_replace('/[^%a-zA-Z0-9 _-]/', '', 'Hello World! (2026)')));
var_dump(preg_replace("/[\r\n]+/", "\n", "a\r\n\r\nb"));
