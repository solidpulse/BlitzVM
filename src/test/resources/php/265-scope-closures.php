<?php
function make_scoped($x) {
    include __DIR__ . '/inc/scope-closures.php';
    echo $byValue(), ':', $byRef(), ':', $arrow(), ":$x\n";
    return [$byValue, $byRef];
}
[$a, $b] = make_scoped(3);
echo $a(), ':', $b(), "\n";
$x = 10;
$outer = function () use (&$x) {
    include __DIR__ . '/inc/scope-closures.php';
    return $byRef();
};
echo $outer(), ":$x\n";
$local = function ($n) use ($x) { extract(['made' => $n]); return "$x:$made"; };
echo $local(7), "\n";
