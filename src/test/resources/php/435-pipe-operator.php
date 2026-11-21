<?php
function identity($value) {
    return $value;
}

function addOne($value) {
    return $value + 1;
}

function echoThen($label, $value) {
    echo $label;
    return $value;
}

function callableFrom($value) {
    return fn ($argument) => $argument . ':' . $value;
}

function byRef(&$value) {
    $value++;
    return $value;
}

function show(string $label, callable $fn): void {
    try {
        $result = $fn();
        var_dump($result);
    } catch (Throwable $e) {
        echo $label, '!', get_class($e), ': ', $e->getMessage(), "\n";
    }
}

var_dump('  hello  ' |> 'trim' |> 'strtoupper');
var_dump(5 |> (fn ($x) => $x * 2));
var_dump(5 |> (static fn ($x) => $x + 3));
var_dump(5 |> identity(...) |> addOne(...));
var_dump([3, 1, 2] |> 'count');
var_dump(5 |> [new class {
    public function run($value) {
        return $value * 4;
    }
}, 'run']);
var_dump(5 |> callableFrom('tail'));

var_dump(1 + 2 |> identity(...));
var_dump(1 - 2 |> addOne(...));
var_dump(1 < 2 |> addOne(...));
var_dump(0 |> addOne(...) == 1);
var_dump(0 |> addOne(...) ?? 9);
var_dump(false ? 0 : 1 |> addOne(...));
var_dump(true ? 0 : 1 |> addOne(...));
var_dump(1 |> addOne(...) | 4);
var_dump(0 |> addOne(...) && false);
var_dump(0 |> addOne(...) || false);
var_dump(0 |> addOne(...) and false);
var_dump(0 |> addOne(...) or true);

$assigned = 1 |> addOne(...);
var_dump($assigned);
$assigned = 1 |> addOne(...) |> addOne(...) |> addOne(...);
var_dump($assigned);

echoThen('left-', 5) |> echoThen('right-', identity(...));
var_dump(7 |> (function ($x) {
    echo "called-";
    return $x + 1;
}));

show('array-count', fn () => 'ignored' |> [new class {
    public function run($value) {
        return strlen($value);
    }
}, 'run']);
show('missing-function', fn () => 5 |> 'no_such_function');
show('not-callable', fn () => 5 |> 3);
show('byref-builtin', fn () => [3, 1, 2] |> 'sort');
show('byref-user', fn () => 1 |> 'byRef');
$number = 1;
show('byref-value-kept', function () use ($number) {
    try {
        1 |> 'byRef';
    } catch (Throwable $e) {
    }
    return $number;
});
