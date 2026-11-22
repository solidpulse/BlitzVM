<?php
function need($a, $b = 2) {
    return "need:$a,$b";
}
function byRef(&$value) {
    $value .= "!";
    return $value;
}

class ErrorCases {
    public function __construct(public $a = 1, public $b = 2) {}
    public function method($x, $y = 2) {
        return "method:$x,$y";
    }
    public static function statik($s, $t = 2) {
        return "static:$s,$t";
    }
}

$cases = [
    "user-unknown" => fn() => need(unknown: 1, a: 0),
    "user-overwrite" => fn() => need(1, a: 2),
    "user-duplicate" => fn() => need(a: 1, a: 2),
    "user-missing" => fn() => need(b: 5),
    "method-unknown" => fn() => (new ErrorCases())->method(nope: 1),
    "method-overwrite" => fn() => (new ErrorCases())->method(1, x: 2),
    "method-duplicate" => fn() => (new ErrorCases())->method(x: 1, x: 2),
    "static-unknown" => fn() => ErrorCases::statik(nope: 1),
    "constructor-unknown" => fn() => new ErrorCases(nope: 1),
    "constructor-overwrite" => fn() => new ErrorCases(1, a: 2),
    "closure-unknown" => fn() => (function ($value) { return $value; })(nope: 1),
    "builtin-unknown" => fn() => trim(nope: "x"),
    "builtin-overwrite" => fn() => str_pad("x", 4, string: "y"),
    "builtin-duplicate" => fn() => str_pad(string: "x", length: 3, string: "y"),
    "builtin-variadic" => fn() => array_push(array: []),
    "builtin-default" => fn() => array_keys(array: [1, 2], strict: true),
    "builtin-ref" => fn() => preg_match(pattern: "/a/", subject: "abc", matches: []),
    "user-ref" => fn() => byRef(value: "x" . "y"),
    "internal-unknown" => fn() => new ReflectionClass(nope: 1),
];

foreach ($cases as $label => $case) {
    try {
        $case();
        echo "$label no-error\n";
    } catch (Throwable $e) {
        echo "$label ", get_class($e), ": ", $e->getMessage(), "\n";
    }
}

for ($i = 0; $i < 6; $i++) {
    try {
        if ($i === 2) {
            echo "$i ", need(nope: 1), "\n";
        } elseif ($i === 4) {
            echo "$i ", need(1, a: 9), "\n";
        } else {
            echo "$i ", need(a: $i, b: $i * 2), "\n";
        }
    } catch (Throwable $e) {
        echo "$i ", get_class($e), ": ", $e->getMessage(), "\n";
    }
}
