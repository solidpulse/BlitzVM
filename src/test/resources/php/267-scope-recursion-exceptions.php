<?php
function recursive_scope($depth) {
    extract(['x' => $depth]);
    if ($depth > 0) { recursive_scope($depth - 1); }
    try { require __DIR__ . '/inc/scope-exception.php'; }
    catch (Exception $e) { echo "$depth:$x:$before_throw:", $e->getMessage(), "\n"; }
}
recursive_scope(2);
