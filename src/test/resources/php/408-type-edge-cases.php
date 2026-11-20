<?php
// Edge cases: untyped declarations are untouched, special types accept only their matches.
function strip($s) {
    return str_replace(__DIR__ . '/', '', $s);
}

function untyped($value) { return $value; }
var_dump(untyped('5'));
var_dump(untyped(5));
var_dump(untyped(null));
var_dump(untyped([1]));

class Plain {
    public $value;
}
$plain = new Plain();
$plain->value = '5';
var_dump($plain->value);
$plain->value = 5;
var_dump($plain->value);

function untypedReturn() { return '5'; }
var_dump(untypedReturn());

function maybeInt(?int $value) { var_dump($value); }
maybeInt(null);
maybeInt('5');
try { maybeInt([]); } catch (TypeError $e) { echo strip($e->getMessage()), "\n"; }

function literalFalse(false $value) { var_dump($value); }
function literalTrue(true $value) { var_dump($value); }
function intOrFalse(int|false $value) { var_dump($value); }
literalFalse(false);
literalTrue(true);
intOrFalse(0);
intOrFalse(false);
intOrFalse(1);
intOrFalse('0');
try { literalFalse(0); } catch (TypeError $e) { echo strip($e->getMessage()), "\n"; }
try { literalTrue(false); } catch (TypeError $e) { echo strip($e->getMessage()), "\n"; }
try { intOrFalse(null); } catch (TypeError $e) { echo strip($e->getMessage()), "\n"; }

function mixedValue(mixed $value) { var_dump($value); }
mixedValue(null);
mixedValue('5');
mixedValue([1]);
mixedValue(5.5);

function objectValue(object $value) { echo get_class($value), "\n"; }
objectValue(new stdClass());
try { objectValue(null); } catch (TypeError $e) { echo strip($e->getMessage()), "\n"; }

class Ancestor {
    public function selfParam(self $other): self { return $other; }
}
class Descendant extends Ancestor {}
class Unrelated {}

echo get_class((new Ancestor())->selfParam(new Descendant())), "\n";
try { (new Ancestor())->selfParam(new Unrelated()); } catch (TypeError $e) { echo strip($e->getMessage()), "\n"; }

class ParentClass {}
class ChildClass extends ParentClass {
    public function parentParam(parent $value): parent { return $value; }
}
echo get_class((new ChildClass())->parentParam(new ParentClass())), "\n";
try { (new ChildClass())->parentParam(new Unrelated()); } catch (TypeError $e) { echo strip($e->getMessage()), "\n"; }

function iterableValue(iterable $value) { echo is_array($value) ? 'array' : get_class($value), "\n"; }
$generator = (function (): Generator { yield 1; })();
iterableValue($generator);
function callableValue(callable $value) { var_dump($value()); }
callableValue(function () { return 'closed'; });
