<?php
function strip($s) {
    return str_replace(__DIR__ . '/', '', $s);
}

class SampleA {}
abstract class SampleAbs {}
interface SampleIface {}

function needTwoArgs($x, $y) {
    return $x + $y;
}

function needAtLeastOne($x, $y = 10) {
    return $x + $y;
}

function trigger($kind) {
    if ($kind === 'undef_fn') return nope_undefined_fn();
    if ($kind === 'undef_method') return (new SampleA())->b();
    if ($kind === 'null_method') { $n = null; return $n->m(); }
    if ($kind === 'undef_const') return NOPE_CONST_42;
    if ($kind === 'abs_class') return new SampleAbs();
    if ($kind === 'iface') return new SampleIface();
    if ($kind === 'missing_class') return new MissingClass42();
    if ($kind === 'intdiv_zero') return intdiv(1, 0);
    if ($kind === 'intdiv_min') return intdiv(PHP_INT_MIN, -1);
    if ($kind === 'mod_zero') { $z = 0; return 1 % $z; }
    if ($kind === 'div_zero') { $z = 0; return 1 / $z; }
    if ($kind === 'too_few_exact') return needTwoArgs(1);
    if ($kind === 'too_few_min') return needAtLeastOne();
    if ($kind === 'builtin_arity') return strlen('a', 'b');
    if ($kind === 'count_type') return count(123);
    if ($kind === 'explode_val') return explode('', 'abc');
    if ($kind === 'str_repeat_val') return str_repeat('a', -2);
    if ($kind === 'array_chunk_val') return array_chunk([1, 2], 0);
    if ($kind === 'json_depth') return json_decode('[]', true, 0);
    if ($kind === 'bad_offset') { $a = []; $a[new stdClass()] = 1; }
}

$kinds = [
    'undef_fn', 'undef_method', 'null_method', 'undef_const',
    'abs_class', 'iface', 'missing_class',
    'intdiv_zero', 'intdiv_min', 'mod_zero', 'div_zero',
    'too_few_exact', 'too_few_min', 'builtin_arity',
    'count_type', 'explode_val', 'str_repeat_val', 'array_chunk_val',
    'json_depth', 'bad_offset',
];

foreach ($kinds as $k) {
    try {
        trigger($k);
        echo "$k => DID NOT THROW
";
    } catch (DivisionByZeroError $e) {
        printf("%s => [DBZ] %s: %s (file=%s line=%d)
", $k, get_class($e), strip($e->getMessage()), basename($e->getFile()), $e->getLine());
    } catch (ArgumentCountError $e) {
        printf("%s => [ACE] %s: %s (file=%s line=%d)
", $k, get_class($e), strip($e->getMessage()), basename($e->getFile()), $e->getLine());
    } catch (TypeError $e) {
        printf("%s => [TE] %s: %s (file=%s line=%d)
", $k, get_class($e), strip($e->getMessage()), basename($e->getFile()), $e->getLine());
    } catch (ValueError $e) {
        printf("%s => [VE] %s: %s (file=%s line=%d)
", $k, get_class($e), strip($e->getMessage()), basename($e->getFile()), $e->getLine());
    } catch (ArithmeticError $e) {
        printf("%s => [AE] %s: %s (file=%s line=%d)
", $k, get_class($e), strip($e->getMessage()), basename($e->getFile()), $e->getLine());
    } catch (Error $e) {
        printf("%s => [ERR] %s: %s (file=%s line=%d)
", $k, get_class($e), strip($e->getMessage()), basename($e->getFile()), $e->getLine());
    }
}

// Verify catching as Throwable also works and Exception does NOT catch Error
try {
    try {
        nope_again();
    } catch (Exception $ex) {
        echo "WRONG: Exception caught Error
";
    }
} catch (Throwable $t) {
    echo "Throwable caught ", get_class($t), ": ", $t->getMessage(), "
";
}
