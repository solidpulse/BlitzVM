<?php
// Internal callback invocations use weak mode for parameters; returns use the declaring
// file's strict_types. The strict closures come from an include with declare(strict_types=1).
require __DIR__ . '/inc/698-strict-closures.php';

function strip(string $message): string {
    return str_replace(__DIR__ . '/', '', $message);
}

var_dump(array_map(fn (int $x) => $x + 1, ["5", 6, 7.0]));
var_dump(array_map(strictIntClosure(), ["5"]));
var_dump(strictMapInsideStrictFile());

$weakReturn = function (): int { return "7"; };
var_dump(array_map($weakReturn, [1]));

try {
    array_map(strictReturnClosure(), [1]);
} catch (TypeError $e) {
    echo 'strict return: ', strip($e->getMessage()), "\n";
}

try {
    array_map(fn (int $x) => $x, ["nope"]);
} catch (TypeError $e) {
    echo 'map param: ', strip($e->getMessage()), "\n";
}
try {
    array_filter(["nope"], fn (int $v) => true);
} catch (TypeError $e) {
    echo 'filter param: ', strip($e->getMessage()), "\n";
}
try {
    array_reduce(["nope"], fn (int $acc, int $v) => $acc, 0);
} catch (TypeError $e) {
    echo 'reduce param: ', strip($e->getMessage()), "\n";
}
$sorted = ['b', 'a'];
try {
    usort($sorted, fn (int $a, int $b) => $a <=> $b);
} catch (TypeError $e) {
    echo 'sort param: ', strip($e->getMessage()), "\n";
}

// The strict include's closure accepts the value a non-strict caller supplies only after
// the same weak coercion.
var_dump(array_map(strictIntClosure(), [5]));
