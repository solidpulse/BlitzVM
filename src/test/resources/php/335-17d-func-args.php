<?php

function edge($a, $b = null) {
    try {
        func_get_arg(-1);
    } catch (ValueError $e) {
        echo get_class($e), ':', $e->getMessage(), "\n";
    }
    try {
        func_get_arg(5);
    } catch (ValueError $e) {
        echo get_class($e), ':', $e->getMessage(), "\n";
    }
    $shown = array_map(fn($v) => var_export($v, true), func_get_args());
    return func_num_args() . ':' . implode(',', $shown);
}

echo edge(1), "\n";
echo edge(1, 2, 3), "\n";
