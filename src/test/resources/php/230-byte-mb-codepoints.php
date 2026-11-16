<?php
$s = "é😀e\u{0301}ß中";
var_dump(strlen($s), mb_strlen($s), mb_strlen($s, '8bit'), mb_ord('😀'), mb_chr(0x1F600) === '😀');
echo mb_substr($s, 1, 3), ':', mb_substr($s, -2), ':', mb_substr($s, 1, -1), "\n";
echo mb_strtoupper('straße é'), ':', mb_strtolower('ÉΣ'), "\n";
var_dump(mb_strpos($s, 'e'), mb_strpos($s, '😀'), mb_strrpos('😀é😀', '😀'), mb_stripos('éAb😀', 'a'));
foreach (mb_str_split($s, 2) as $part) { echo bin2hex($part), ':'; }
echo "\n";
var_dump(mb_substr_count('😀é😀é', '😀'), mb_strwidth("e\u{0301}😀中"));
echo mb_str_pad('😀é', 5, '中', STR_PAD_BOTH), "\n";
var_dump(mb_check_encoding($s), mb_check_encoding("\xE9"), mb_ord("\xFF"), mb_chr(0xD800));
