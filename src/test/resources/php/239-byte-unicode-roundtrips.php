<?php
$s = 'é😀éß中';
$encodings = ['UTF-8', 'UTF-16BE', 'UTF-16LE', 'UTF-32BE', 'UTF-32LE'];
foreach ($encodings as $encoding) {
    $encoded = mb_convert_encoding($s, $encoding, 'UTF-8');
    var_dump(mb_check_encoding($encoded, $encoding), mb_strlen($encoded, $encoding), mb_convert_encoding($encoded, 'UTF-8', $encoding) === $s);
}
echo normalizer_normalize("e\u{0301}"), ':', mb_strtoupper('ß'), ':', mb_substr('😀é😀', -2, 1), "\n";
$r = preg_replace_callback('/(😀)/u', function($m) { return $m[1][0] . 'é' . $m[1][1]; }, 'a😀b', -1, $count, PREG_OFFSET_CAPTURE);
echo bin2hex($r), ':', $count, "\n";
