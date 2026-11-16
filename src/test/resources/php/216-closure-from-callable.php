<?php
function incrementCallable($n) { return $n + 1; }
class CallableSource {
    private $value = 7;
    private function secret($n) { return $this->value + $n; }
    public function expose() { return Closure::fromCallable([$this, 'secret']); }
    public function publicMethod($n) { return $this->value * $n; }
    public static function staticMethod($n) { return $n * 2; }
    public function __invoke($n) { return $n * 3; }
}
$o = new CallableSource;
$c = $o->expose(); echo $c(2), "\n";
echo Closure::fromCallable('incrementCallable')(4), "\n";
echo Closure::fromCallable('strlen')('abcd'), "\n";
echo Closure::fromCallable([$o, 'publicMethod'])(3), "\n";
echo Closure::fromCallable([CallableSource::class, 'staticMethod'])(6), "\n";
echo Closure::fromCallable($o)(3), "\n";
$f = fn($x) => $x + 5;
var_dump(Closure::fromCallable($f) === $f);
try { Closure::fromCallable([$o, 'secret']); } catch (TypeError $e) { echo $e->getMessage(), "\n"; }
