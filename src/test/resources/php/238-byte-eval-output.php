<?php
$code = 'return "é😀\xFF";';
$s = eval($code);
echo bin2hex($s), ':', strlen($s), "\n";
eval('echo "é😀\xFF";');
echo "\n";
ob_start(function($s) { return $s . "\xFF"; });
echo "é😀\x80";
ob_end_flush();
echo "\n";
$s = str_repeat("é’😀\xFF", 1000);
ob_start(); echo $s; $out = ob_get_clean();
var_dump(strlen($out), $out === $s, base64_decode(base64_encode($out)) === $s);
var_dump(eval('1 + 1;'));
