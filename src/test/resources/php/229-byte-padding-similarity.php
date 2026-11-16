<?php
$s = 'é😀';
echo bin2hex(sprintf('[%10s][%.3s][%-9s]', $s, $s, $s)), "\n";
echo bin2hex(str_pad('é', 5, '😀', STR_PAD_BOTH)), "\n";
echo bin2hex(strrev($s)), ':', bin2hex(chunk_split($s, 3, '|')), "\n";
foreach (str_split($s, 2) as $part) { echo bin2hex($part), ':'; }
echo "\n";
echo bin2hex(wordwrap('éé é😀', 4, '|', true)), "\n";
$n = similar_text('éa😀', 'éb😀', $percent);
echo $n, ':', sprintf('%.2f', $percent), ':', levenshtein('éa😀', 'éb😀'), ':', levenshtein('é', 'e'), "\n";
ob_start();
$n = printf('%5s', 'é');
$out = ob_get_clean();
echo $n, ':', bin2hex($out), "\n";
var_dump('é', "\xFF\0");
