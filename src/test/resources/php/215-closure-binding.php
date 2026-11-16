<?php
class ScopeA {
    private $value = 'A';
    const NAME = 'scope-a';
    public function closure(&$n) { return function($suffix) use (&$n) { $n++; return $this->value . ':' . self::NAME . ':' . static::NAME . ':' . $suffix . ':' . $n; }; }
}
class ScopeB {
    private $value = 'B';
    const NAME = 'scope-b';
}
$n = 0;
$a = new ScopeA;
$c = $a->closure($n);
echo $c('first'), "\n";
$bound = $c->bindTo(new ScopeB, ScopeB::class);
echo $bound('second'), "\n";
$bound = Closure::bind($c, new ScopeB, new ScopeB);
echo $bound('third'), "\n";
echo $c('original'), "\n";
echo $c->call(new ScopeB, 'call'), "\n";
echo $c->__invoke('invoke'), "\n";
$plain = function () { return self::NAME; };
echo $plain->bindTo(null, ScopeB::class)(), "\n";
$counter = function () { static $n = 0; return ++$n; };
echo $counter(), '|';
$copy = $counter->bindTo(null);
echo $counter(), ':', $copy(), ':', $counter(), ':', $copy(), "\n";
echo $counter->call(new ScopeB), ':', $counter(), "\n";
