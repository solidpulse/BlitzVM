<?php
namespace App\Checks {
    function run(array $values): void {
        foreach ($values as $label => $v) {
            echo $label, ': ',
                var_export(is_string($v), true), ' ', var_export(is_array($v), true), ' ',
                var_export(is_int($v), true), ' ', var_export(is_integer($v), true), ' ', var_export(is_long($v), true), ' ',
                var_export(is_float($v), true), ' ', var_export(is_double($v), true), ' ',
                var_export(is_bool($v), true), ' ', var_export(is_null($v), true), ' ',
                var_export(is_object($v), true), "\n";
        }
    }
    function lengths(array $values): void {
        foreach ($values as $label => $v) {
            try {
                echo $label, ' strlen: ', var_export(strlen($v), true), "\n";
            } catch (\TypeError $e) {
                echo $label, ' strlen: ', get_class($e), ': ', $e->getMessage(), "\n";
            }
        }
    }
    function keys(): void {
        $a = ['a' => 1, 'n' => null, 5 => 'x', '' => 'empty'];
        foreach (['a', 'n', 'z', 5, '5', 5.7, null, true, false] as $k) {
            try {
                echo var_export($k, true), ' => ', var_export(array_key_exists($k, $a), true), "\n";
            } catch (\TypeError $e) {
                echo var_export($k, true), ' => ', get_class($e), ': ', $e->getMessage(), "\n";
            }
        }
        try {
            var_dump(array_key_exists('a', new \ArrayObject(['a' => 1])));
        } catch (\TypeError $e) {
            echo get_class($e), ': ', $e->getMessage(), "\n";
        }
        try {
            var_dump(array_key_exists([], $a));
        } catch (\TypeError $e) {
            echo get_class($e), ': ', $e->getMessage(), "\n";
        }
    }
    function arity(): void {
        try {
            var_dump(is_string());
        } catch (\ArgumentCountError $e) {
            echo get_class($e), ': ', $e->getMessage(), "\n";
        }
        try {
            var_dump(strlen('a', 'b'));
        } catch (\ArgumentCountError $e) {
            echo get_class($e), ': ', $e->getMessage(), "\n";
        }
    }
    function loopType($i) { return is_int($i) && is_string((string) $i) && !is_array($i) && strlen((string) $i) > 0 && array_key_exists($i % 3, [1, 2, 3]); }
    function undefinedVar(): void {
        var_dump(is_null($nope));
    }
}

namespace App\Shadowed {
    function is_string($v): string { return 'shadow:' . gettype($v); }
    function strlen(&$v): int { $v = 'changed'; return 99; }
    function check(): void {
        $s = 'abc';
        var_dump(is_string($s), is_string([1]));
        var_dump(strlen($s), $s);
        $arr = ['k' => 'v'];
        var_dump(strlen($arr['k']), $arr['k']);
    }
}

namespace App\Late {
    function probe($v) { return is_array($v); }
    function first(): void {
        var_dump(probe([1]), probe('x'));
    }
}

namespace {
    $values = ['int' => 1, 'float' => 1.5, 'string' => 'hi', 'empty' => '', 'array' => [], 'null' => null,
        'true' => true, 'false' => false, 'object' => new stdClass(), 'closure' => fn() => 1];
    App\Checks\run($values);
    App\Checks\lengths(['string' => 'héllo', 'int' => 12345, 'float' => 1.5, 'null' => null, 'bool' => true,
        'zero' => 0, 'neg' => -12]);
    App\Checks\keys();
    App\Checks\arity();
    App\Checks\undefinedVar();
    App\Shadowed\check();
    App\Late\first();
    $calls = 0;
    for ($i = 0; $i < 3000; $i++) {
        if (App\Checks\loopType($i)) { $calls++; }
    }
    echo $calls, "\n";
}
