<?php
function sum3($a, $b, $c) {
    return $a + $b + $c;
}
$nums = [10, 20, 30];
echo "fn=" . sum3(...$nums) . "
";
echo "fn_mix=" . sum3(5, ...[15, 25]) . "
";

class Adder {
    public $base;
    public function __construct($a, $b) {
        $this->base = $a + $b;
    }
    public function calc($x, $y, $z) {
        return $this->base + $x * $y + $z;
    }
    public static function joinStr($sep, ...$parts) {
        return implode($sep, $parts);
    }
}

class SubAdder extends Adder {
    public function __construct(...$args) {
        parent::__construct(...$args);
    }
}

$sub = new SubAdder(...[3, 7]);
echo "method=" . $sub->calc(...[2, 4, 5]) . "
";
echo "static=" . Adder::joinStr('-', ...['a', 'b', 'c']) . "
";

echo "max=" . max(...[4, 19, 2, 8]) . "
";
echo "sprintf=" . sprintf('%s:%d:%s', ...['ok', 42, 'end']) . "
";
$merged = array_merge(...[[1, 2], ['k' => 'v'], [3]]);
echo "merge=" . json_encode($merged) . "
";
