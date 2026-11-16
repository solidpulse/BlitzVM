<?php
function paths() {
    $name = 'arr';
    $$name = ['a' => 1];
    $$name['a'] += 2;
    $r = &$$name['a']; $r++;
    echo $arr['a'], ':', isset($$name['a']) ? 'set' : 'unset', "\n";
    $name = 'box'; $$name = new stdClass;
    $$name->p = 5; $$name->p++;
    echo $box->p, "\n";
    $name = 'key';
    foreach (['x' => 7, 'y' => 8] as $$name => ${'val'}) { echo "$key:$val\n"; }
    $name = 'refval';
    $list = [1, 2];
    foreach ($list as &$$name) { $$name *= 3; }
    unset($$name);
    echo json_encode($list), "\n";
}
paths();
