<?php
// count() through Countable on two classes at the same call site.
final class CountedA implements Countable {
    private array $d;
    public function __construct() { $this->d = range(0, 3); }
    public function count(): int { return count($this->d); }
}
final class CountedB implements Countable {
    private array $d;
    public function __construct() { $this->d = range(0, 6); }
    public function count(): int { return count($this->d); }
}

function total(object $a, object $b): int {
    $sum = 0;
    foreach ([$a, $b] as $o) { $sum += count($o); }
    return $sum;
}
$c = new CountedA();
$d = new CountedB();
var_dump(count($c));
var_dump(count($d));
var_dump(sizeof($c));
var_dump(total($c, $d));
foreach ([$c, $d] as $o) { echo count($o), "\n"; }
