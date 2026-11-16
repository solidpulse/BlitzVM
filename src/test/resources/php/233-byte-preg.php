<?php
$s = 'é😀a';
foreach (['/./s', '/./su'] as $re) {
    $n = preg_match_all($re, $s, $m, PREG_OFFSET_CAPTURE);
    echo $re, ':', $n, "\n";
    foreach ($m[0] as $pair) { echo bin2hex($pair[0]), '@', $pair[1], ':'; }
    echo "\n";
}
var_dump(preg_match('/\xC3\xA9/', $s), preg_match('/é/u', $s), preg_match('/\w+/', 'é'), preg_match('/\w+/u', 'é'));
var_dump(preg_match('/é/i', 'É'), preg_match('/é/iu', 'É'));
preg_match('/😀/u', $s, $m, PREG_OFFSET_CAPTURE, 2);
echo bin2hex($m[0][0]), '@', $m[0][1], "\n";
var_dump(preg_match('/./u', $s, $m, 0, 1), preg_last_error(), preg_last_error_msg());
var_dump(preg_match('/./u', "\xFF", $m), preg_last_error(), preg_last_error_msg());
var_dump(preg_match('/./', "\xFF", $m), preg_last_error(), bin2hex($m[0]));
echo preg_replace('/(é)(😀)/u', '$2:$1', $s), "\n";
echo preg_replace_callback('/./u', function($m) { return '[' . $m[0] . ']'; }, $s), "\n";
foreach (preg_split('/😀/u', 'é😀a😀b', -1, PREG_SPLIT_OFFSET_CAPTURE) as $pair) {
    echo bin2hex($pair[0]), '@', $pair[1], ':';
}
echo "\n";
