<?php
function variables($name) {
    $$name = 3;
    $$name += 4;
    echo $$name++, ':', ++$$name, ":$value\n";
    ${'val' . 'ue'} *= 2;
    echo "$value:", isset($$name) ? 'set' : 'unset', ':', empty($$name) ? 'empty' : 'nonempty', "\n";
    $alias = &$$name;
    $alias += 1;
    echo "$value\n";
    unset($$name);
    echo isset($value) ? 'set' : 'unset', ":$alias\n";
    $$name = 2;
    echo "$value:$alias\n";
}
variables('value');
$name = 'top'; $$name = 8; echo "$top\n";
