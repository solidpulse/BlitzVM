<?php
function nested($x) {
    echo require __DIR__ . '/inc/scope-nested.php', ":$x:$deep\n";
    $name = 'never_named'; echo $$name, "\n";
}
nested(1); nested(10);
function once_a() {
    $x = 0;
    var_dump(include_once __DIR__ . '/inc/scope-once.php');
    echo "$x:", $once ?? 'none', "\n";
}
function once_b() {
    $x = 9;
    var_dump(require_once __DIR__ . '/inc/scope-once.php');
    echo "$x:", $once ?? 'none', "\n";
}
once_a(); once_b(); once_a();
