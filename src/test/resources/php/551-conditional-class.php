<?php
interface Marker {}
abstract class Base implements Marker {}
class Solid extends Base {}

function takeCond(Cond $c): Cond { return $c; }
function takeMissing(NeverDeclared $x): NeverDeclared { return $x; }

$early = new stdClass();
var_dump($early instanceof Cond);
var_dump($early instanceof NeverDeclared);

if (PHP_VERSION_ID > 0) {
    class Cond extends Base {
        public function name(): string { return 'cond'; }
    }
} else {
    class Cond extends Base {}
}

if (PHP_VERSION_ID < 0) {
    class NeverDeclared extends Base {}
}

$cond = new Cond();
var_dump(takeCond($cond) === $cond);
var_dump($cond instanceof Cond, $cond instanceof Base, $cond instanceof Marker);
echo $cond->name(), "\n";

$solid = new Solid();
var_dump($solid instanceof Cond);
var_dump($cond instanceof Solid);

try { takeMissing($solid); } catch (TypeError $e) { echo preg_replace('/, called in .*$/', '', $e->getMessage()), "\n"; }

class Holder {
    public Cond $item;
    public ?NeverDeclared $missing = null;
}
$h = new Holder();
$h->item = $cond;
var_dump($h->item === $cond);
try { $h->item = $solid; } catch (TypeError $e) { echo $e->getMessage(), "\n"; }
