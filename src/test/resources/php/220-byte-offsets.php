<?php
$s = "éa<b😀’";
var_dump(strlen($s), strpos($s, 'a'), strcspn($s, '<'), substr($s, 2));
var_dump($s[0] === "\xC3", "éa<b"[0] === "\xC3", chr(195) . chr(169) === 'é');
foreach ([0, 1, 2, -1, -4] as $i) { echo $i, ':', bin2hex($s[$i]), "\n"; }
echo bin2hex(substr($s, 1, 4)), ':', bin2hex(substr($s, -7, 4)), "\n";
var_dump(strspn($s, "\xC3\xA9"), strcspn($s, '<', 2), strpos($s, '😀'), strrpos($s, '’'));
$t = 'éa';
$t[0] = "\xFF";
$t[-1] = 'b';
echo bin2hex($t), "\n";
$t[5] = "\x80";
echo bin2hex($t), "\n";
echo bin2hex(substr_replace($s, "\xFF", 1, 2)), "\n";
