<?php

function triple($n) { return $n * 3; }

class FccBase {
    public static function who() { return static::class; }
}

class FccClass extends FccBase {
    private int $acc = 10;
    public function add($n) { return $this->acc += $n; }
    public static function mul($a, $b) { return $a * $b; }
    public function makeSelfAndStatic(): array {
        return [self::who(...), static::who(...)];
    }
    public static function callParentWho(): Closure {
        return parent::who(...);
    }
}

class FccMagic {
    public function __call($name, $args) { return $name . '(' . implode(',', $args) . ')'; }
    public static function __callStatic($name, $args) { return 'static:' . $name . '(' . implode(',', $args) . ')'; }
}

$f1 = strlen(...);
$f2 = triple(...);
$obj = new FccClass();
$mName = 'add';
$f3 = $obj->add(...);
$f4 = $obj->$mName(...);
$f5 = FccClass::mul(...);
$pair = $obj->makeSelfAndStatic();
$fSelf = $pair[0];
$fStatic = $pair[1];
$fParent = FccClass::callParentWho();
$mag = new FccMagic();
$fMag1 = $mag->greet(...);
$fMag2 = FccMagic::hello(...);
$strFn = 'strtoupper';
$fFromExpr = $strFn(...);

echo $f1('hello'), '|', $f2(7), '|', $f3(5), '|', $f4(2), '|', $obj->add(0), "\n";
echo $f5(6, 7), '|', $fSelf(), '|', $fStatic(), '|', $fParent(), "\n";
echo $fMag1('a', 'b'), '|', $fMag2(1, 2), '|', $fFromExpr('hi'), "\n";
var_dump($f1 instanceof Closure, $f3 instanceof Closure, $f5 instanceof Closure, is_callable($f2));
