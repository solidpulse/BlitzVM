<?php
// Weak-mode scalar parameter coercion: php 8.5 rules for int, float, string, bool.
function strip($s) {
    return str_replace(__DIR__ . '/', '', $s);
}

set_error_handler(function (int $errno, string $message) {
    echo 'DEPRECATED: ', $message, "\n";
    return true;
});

function asInt(int $x) { echo 'int(', var_export($x, true), ")\n"; }
function asFloat(float $x) { echo 'float(', var_export($x, true), ")\n"; }
function asString(string $x) { echo 'string(', var_export($x, true), ")\n"; }
function asBool(bool $x) { echo 'bool(', var_export($x, true), ")\n"; }

foreach (['5', '1e3', ' 5', 5.0, true, false, '0'] as $value) {
    try { asInt($value); } catch (TypeError $e) { echo strip($e->getMessage()), "\n"; }
}
foreach ([5.7, '5.5', '5.0'] as $value) {
    try { asInt($value); } catch (TypeError $e) { echo strip($e->getMessage()), "\n"; }
}
foreach (['5 apples', '', null, []] as $value) {
    try { asInt($value); } catch (TypeError $e) { echo strip($e->getMessage()), "\n"; }
}
foreach ([3, '3.25', '1e2', true, false] as $value) {
    try { asFloat($value); } catch (TypeError $e) { echo strip($e->getMessage()), "\n"; }
}
foreach ([12, 1.5, true, false] as $value) {
    try { asString($value); } catch (TypeError $e) { echo strip($e->getMessage()), "\n"; }
}
foreach (['0', '', 'abc', 0, 1, 0.0, 2.5, []] as $value) {
    try { asBool($value); } catch (TypeError $e) { echo strip($e->getMessage()), "\n"; }
}
try { asString(new stdClass()); } catch (TypeError $e) { echo strip($e->getMessage()), "\n"; }
class ToText {
    public function __toString(): string { return 'made'; }
}
asString(new ToText());
