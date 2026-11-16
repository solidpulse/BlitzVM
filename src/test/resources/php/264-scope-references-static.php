<?php
$g = 1;
function refs_statics($arg) {
    global $g;
    static $s = 0;
    $s++;
    $a = 1; $b = $arg;
    $old = &$a;
    include __DIR__ . '/inc/scope-references.php';
    $new += 2;
    echo "$a:$b:$old:$new:$g:$n:$s\n";
    $name = 's'; $$name++;
}
refs_statics(10); refs_statics(20);
echo "$g\n";
