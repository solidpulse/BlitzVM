<?php
function observe(&$s) {
    $s .= 'b';
    $args = func_get_args(); $arg = func_get_arg(0);
    $trace = debug_backtrace(); $traceArg = $trace[0]['args'][0];
    $s .= 'c'; echo $args[0], '|', $arg, '|', $traceArg, '|', $s, "\n";
    var_dump(get_defined_vars()['s']);
    echo json_encode(compact('s')), '|', serialize([$s]), "\n";
}
function observations() {
    $s = ''; $s .= 'a'; observe($s);
    var_dump($s); echo strtoupper($s), '|', substr($s, 1), '|', str_replace('b', 'B', $s), "\n";
    $f = 'strlen'; echo $f($s), '|', call_user_func('strlen', $s), '|', strlen(...[$s]), "\n";
}
observations();
