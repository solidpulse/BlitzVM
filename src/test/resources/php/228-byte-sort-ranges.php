<?php
$a = ["\xFF", 'é', "\x80", 'z', '😀', '10', '2', 'A', 'a'];
sort($a, SORT_STRING);
foreach ($a as $s) { echo bin2hex($s), ':'; }
echo "\n";
$b = ['a10', 'A2', 'É1', 'é1'];
sort($b, SORT_NATURAL | SORT_FLAG_CASE);
echo implode('|', $b), "\n";
var_dump("\x80" < 'é', 'é' < '😀', strcmp("\xFF", 'é') > 0);
echo bin2hex(trim("\x80\x81a\xFE\xFF", "\x80..\xFF")), "\n";
echo trim('abcHellozyx', 'a..z'), ':', ltrim('123x321', '0..9'), ':', rtrim('123x321', '0..9'), "\n";
echo bin2hex(strtr('é', "\xC3\xA9", "\xFF\x80")), "\n";
echo addcslashes("é\xFF\n", "\x80..\xFF\0..\x1F"), "\n";
echo bin2hex(stripcslashes('\\303\\251\\377\\0')), "\n";
