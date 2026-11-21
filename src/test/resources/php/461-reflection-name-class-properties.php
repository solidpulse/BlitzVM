<?php
set_error_handler(function ($n, $s) { echo "warning:$s\n"; return true; });

class A { public $p; const C = 1; function m($x) {} }
class B extends A { public $q; }
enum E { case X; }
enum F: string { case Y = 'y'; }

print_r(new ReflectionClass('A'));
print_r(new ReflectionObject(new B));
print_r(new ReflectionEnum('E'));
print_r(new ReflectionMethod('B', 'm'));
print_r(new ReflectionProperty('B', 'p'));
print_r(new ReflectionProperty('B', 'q'));
print_r(new ReflectionClassConstant('B', 'C'));
print_r(new ReflectionFunction('strlen'));
print_r((new ReflectionMethod('A', 'm'))->getParameters()[0]);
print_r(new ReflectionEnumUnitCase('E', 'X'));
print_r(new ReflectionEnumBackedCase('F', 'Y'));
echo "\n";

$rc = new ReflectionClass('B');
var_dump($rc->name, $rc->getProperty('p')->class, $rc->getProperty('p')->name);
var_dump($rc->getMethods()[0]->name, $rc->getMethods()[0]->class);
var_dump($rc->getReflectionConstant('C')->class, $rc->getParentClass()->name);
var_dump(isset($rc->name), property_exists($rc, 'name'), get_object_vars($rc));
var_dump((array) new ReflectionMethod('A', 'm'));
$closureReflection = new ReflectionFunction(function () {});
var_dump(preg_replace('/^\{closure:.*:\d+\}$/', '{closure:FILE:LINE}', $closureReflection->name));
$o = new stdClass; $o->dyn = 1;
print_r(new ReflectionProperty($o, 'dyn'));
echo "\n";

$rp = new ReflectionProperty('ReflectionClass', 'name');
var_dump($rp->isPublic(), $rp->isReadOnly(), (string) $rp->getType(), $rp->getModifiers());
$props = array_map(fn ($p) => $p->class . '::' . $p->name, (new ReflectionClass('ReflectionMethod'))->getProperties());
sort($props);
var_dump($props);

// The polyfill-deepclone pattern Symfony's cache uses.
$r = new ReflectionClass(B::class);
$names = [];
foreach ($r->getProperties() as $p) { $names[] = $p->class . '::' . $p->name; }
var_dump($names);
