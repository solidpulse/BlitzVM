<?php
class Magic {
    public function __call($name, $arguments) {
        return $name . ":" . json_encode($arguments);
    }
    public static function __callStatic($name, $arguments) {
        return "static:" . $name . ":" . json_encode($arguments);
    }
    public function __invoke($first, $second = "s") {
        return "invoke:$first,$second";
    }
}

$magic = new Magic();
echo $magic->missing(1, two: 2, three: 3), "\n";
echo $magic->namedOnly(alpha: "a"), "\n";
echo Magic::absent(beta: "b", gamma: 3), "\n";
echo Magic::none(), "\n";
echo $magic(second: 2, first: 1), "\n";

try {
    $magic->duplicate(one: 1, one: 2);
} catch (Error $e) {
    echo get_class($e), ": ", $e->getMessage(), "\n";
}

class CallablePair {
    public function combine($left, $right = "r") {
        return "pair:$left,$right";
    }
    public static function build($a, $b = "b") {
        return "built:$a,$b";
    }
}
$pair = new CallablePair();
$callable = [$pair, "combine"];
echo $callable(right: "R", left: "L"), "\n";
$staticCallable = ["CallablePair", "build"];
echo $staticCallable(b: "B", a: "A"), "\n";
$closure = strlen(...);
echo $closure(string: "four"), "\n";
