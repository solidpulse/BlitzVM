<?php
class Point {
    public $x;
    public $y;
    public function __construct($x = 0, $y = 0) {
        $this->x = $x;
        $this->y = $y;
    }
}
class NoConstructor { public $value = 'default'; }
abstract class AbstractThing { public function __construct($x = 1) {} }
interface AnInterface {}
trait AThing {}
enum AEnum { case One; }
class PrivateConstructor { private function __construct() {} }

function attempt($label, $fn) {
    try {
        $result = $fn();
        echo $label, '=ok', is_object($result) ? ':' . get_class($result) : '', "\n";
    } catch (Throwable $e) {
        echo $label, '=', get_class($e), ': ', $e->getMessage(), "\n";
    }
}

$point = new ReflectionClass('Point');
$p = $point->newInstance(3, 4);
echo 'new=', get_class($p), '|', $p->x, '|', $p->y, "\n";
$q = $point->newInstanceArgs([5, 6]);
echo 'newArgs=', $q->x, '|', $q->y, "\n";
$named = $point->newInstanceArgs(['y' => 8, 'x' => 7]);
echo 'named=', $named->x, '|', $named->y, "\n";
$bare = (new ReflectionClass('NoConstructor'))->newInstanceWithoutConstructor();
echo 'without=', get_class($bare), '|', $bare->value, "\n";
$noCtor = (new ReflectionClass('NoConstructor'))->newInstance();
echo 'noCtor=', get_class($noCtor), '|', $noCtor->value, "\n";
echo 'isInstance=', (int)$point->isInstance($p), ' other=', (int)$point->isInstance(new NoConstructor()), "\n";

attempt('no ctor args', fn() => (new ReflectionClass('NoConstructor'))->newInstanceArgs([1]));
attempt('named unknown', fn() => $point->newInstanceArgs(['z' => 1]));
attempt('interface', fn() => (new ReflectionClass('AnInterface'))->newInstance());
attempt('trait', fn() => (new ReflectionClass('AThing'))->newInstance());
attempt('abstract', fn() => (new ReflectionClass('AbstractThing'))->newInstance());
attempt('enum', fn() => (new ReflectionClass('AEnum'))->newInstance());
attempt('private ctor', fn() => (new ReflectionClass('PrivateConstructor'))->newInstance());
attempt('without abstract', fn() => (new ReflectionClass('AbstractThing'))->newInstanceWithoutConstructor());
attempt('unknown class', fn() => new ReflectionClass('MissingClass'));
