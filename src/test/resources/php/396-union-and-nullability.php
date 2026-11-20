<?php
// Union coercion order and nullability.
function strip($s) {
    return str_replace(__DIR__ . '/', '', $s);
}

set_error_handler(function (int $errno, string $message) {
    echo 'DEPRECATED: ', $message, "\n";
    return true;
});

function intOrString(int|string $x) { echo 'int|string ', var_export($x, true), "\n"; }
function floatOrString(float|string $x) { echo 'float|string ', var_export($x, true), "\n"; }
function intOrFloat(int|float $x) { echo 'int|float ', var_export($x, true), "\n"; }
function stringOrBool(string|bool $x) { echo 'string|bool ', var_export($x, true), "\n"; }
function intOrBool(int|bool $x) { echo 'int|bool ', var_export($x, true), "\n"; }
function allFour(int|float|string|bool $x) { echo 'all ', var_export($x, true), "\n"; }
function maybeInt(?int $x) { echo 'maybe ', var_export($x, true), "\n"; }
function explicitNull(int|string|null $x) { echo 'explicit ', var_export($x, true), "\n"; }
function noNull(int|string $x) { echo 'nonnull ', var_export($x, true), "\n"; }

foreach (['5', 4.5, 'abc', '5.5', true, null] as $value) {
    foreach (['intOrString', 'floatOrString', 'intOrFloat', 'stringOrBool', 'intOrBool', 'allFour'] as $fn) {
        try { $fn($value); } catch (TypeError $e) { echo strip($e->getMessage()), "\n"; }
    }
}
maybeInt(null);
maybeInt('12');
explicitNull(null);
explicitNull(7);
try { noNull(null); } catch (TypeError $e) { echo strip($e->getMessage()), "\n"; }
try { noNull([1]); } catch (TypeError $e) { echo strip($e->getMessage()), "\n"; }
function nullableIterable(): ?iterable { return 1; }
try { nullableIterable(); } catch (TypeError $e) { echo strip($e->getMessage()), "\n"; }
function iterableArg(iterable $x) { echo gettype($x), "\n"; }
iterableArg([1]);
iterableArg(new ArrayIterator([1]));
try { iterableArg(1); } catch (TypeError $e) { echo strip($e->getMessage()), "\n"; }
