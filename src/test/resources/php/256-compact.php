<?php
function literals($a) {
    $b = 2; $c = null;
    echo json_encode(compact('a', 'b', 'c', 'a')), "\n";
}
literals(1);
function computed($a) {
    $b = 3; $c = 4; $names = ['a', ['b', ['c', 'a']]];
    echo json_encode(compact($names, 'b')), "\n";
    extract(['made' => 5]);
    echo json_encode(compact('made')), "\n";
}
computed(2);
set_error_handler(function ($n, $s) { echo "warning:$s\n"; return true; });
echo json_encode(compact('undefined_scope_name')), "\n";
restore_error_handler();
