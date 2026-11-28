<?php
// A callback runs from an internal frame: php prints [internal function] and the builtin's
// own frame, and debug_backtrace() inside the callback sees those same frames.

$thrower = function ($v) { throw new RuntimeException("boom $v"); };
try {
    array_map($thrower, [1]);
} catch (RuntimeException $e) {
    echo "map trace:\n", $e->getTraceAsString(), "\n";
}

try {
    array_filter([1], function ($v) { throw new LogicException('filter'); });
} catch (LogicException $e) {
    echo "filter trace:\n", $e->getTraceAsString(), "\n";
}

try {
    array_reduce([1], function ($acc, $v) { throw new DomainException('reduce'); }, 0);
} catch (DomainException $e) {
    echo "reduce trace:\n", $e->getTraceAsString(), "\n";
}

$sorted = [2, 1];
try {
    usort($sorted, function ($a, $b) { throw new LengthException('sort'); });
} catch (LengthException $e) {
    echo "sort trace:\n", $e->getTraceAsString(), "\n";
}

$backtrace = function ($v) {
    foreach (debug_backtrace(DEBUG_BACKTRACE_IGNORE_ARGS) as $i => $frame) {
        $where = isset($frame['file']) ? basename($frame['file']) . ':' . $frame['line'] : '[internal]:0';
        echo 'bt', $i, ' ', $frame['function'] ?? '?', ' ', $where, "\n";
    }
    return $v;
};
array_map($backtrace, [1]);
array_reduce([1], fn ($acc, $v) => $backtrace($v), 0);

// Warnings raised inside a callback point at the callback's line.
set_error_handler(function ($no, $str, $file, $line) {
    echo 'warn ', $no, ' ', $str, ' ', basename($file), ':', $line, "\n";
    return true;
});
array_map(function ($v) {
    $a = [];
    return $a['missing'] + $v;
}, [1]);
array_filter([1], function ($v) {
    $a = [];
    return $a['missing'] + $v;
});
array_reduce([1], function ($acc, $v) {
    return $undefinedInReduce + $acc;
}, 0);
$sorted = [2, 1];
usort($sorted, function ($a, $b) {
    return $undefinedInSort <=> $b;
});

// A by-reference callback parameter warns once per element and receives a value.
$byref = function (&$x) { return $x + 1; };
var_dump(array_map($byref, [1, 2]));
$byrefFilter = function (&$x) { return $x > 1; };
var_dump(array_filter([1, 2], $byrefFilter));
$byrefReduce = function (&$acc, &$v) { return ($acc ?? 0) + $v; };
var_dump(array_reduce([1, 2], $byrefReduce, 0));
$byrefSort = function (&$a, &$b) { return $a <=> $b; };
$sorted = [2, 1];
usort($sorted, $byrefSort);
var_dump($sorted);
restore_error_handler();
