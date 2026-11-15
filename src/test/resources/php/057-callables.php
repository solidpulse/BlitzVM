<?php

function doubleIt($x) { return $x * 2; }

class Runner {
    public $prop;
    public $callbacks = [];
    public function __construct() {
        $this->prop = fn($x) => $x + 10;
        $this->callbacks['k'] = ['function' => function ($x) { return $x * 3; }];
    }
    public function instanceMethod($x) { return $x + 100; }
    public static function staticMethod($x) { return $x + 200; }
}

class Invokable {
    private int $base;
    public function __construct(int $base) { $this->base = $base; }
    public function __invoke($x, $y = 0) { return $this->base + $x + $y; }
}

class MagicHandler {
    public function __call($name, $args) { return 'call:' . $name . ':' . implode(',', $args); }
    public static function __callStatic($name, $args) { return 'static:' . $name . ':' . implode(',', $args); }
}

$r = new Runner();
$inv = new Invokable(1000);
$mag = new MagicHandler();

echo ($r->prop)(5), '|', $r->callbacks['k']['function'](5), "\n";
echo [$r, 'instanceMethod'](5), '|', ['Runner', 'staticMethod'](5), '|', 'Runner::staticMethod'(5), "\n";
echo 'doubleIt'(7), '|', 'strtoupper'('hello'), '|', $inv(20, 3), "\n";
echo [$mag, 'dyn'](1, 2), '|', ['MagicHandler', 'dynS'](3, 4), '|', 'MagicHandler::dynS'(5), "\n";

echo call_user_func('doubleIt', 9), '|', call_user_func($inv, 1, 2), '|',
     call_user_func_array([$r, 'instanceMethod'], [50]), "\n";

$arrArg = [1, 2];
$fnArr = function ($a) { $a[] = 99; return count($a); };
echo call_user_func($fnArr, $arrArg), '|', call_user_func_array($fnArr, [$arrArg]), '|', count($arrArg), "\n";

var_dump(
    is_callable(fn($x) => $x),
    is_callable('doubleIt'),
    is_callable('strlen'),
    is_callable('Runner::staticMethod'),
    is_callable([$r, 'instanceMethod']),
    is_callable(['Runner', 'staticMethod']),
    is_callable($inv),
    is_callable([$mag, 'anything']),
    is_callable(['MagicHandler', 'anything']),
    is_callable(123),
    is_callable('no_such_fn'),
    is_callable('Runner::instanceMethod'),
    is_callable(['Runner', 'instanceMethod']),
    is_callable($r),
    is_callable([1 => 'Runner', 2 => 'staticMethod'])
);
