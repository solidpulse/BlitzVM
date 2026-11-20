<?php
// Non-scalar declared types are checks, never coercions.
function strip($s) {
    return str_replace(__DIR__ . '/', '', $s);
}

class Base {}
class Child extends Base {}
interface Contract {}
class ContractImpl implements Contract {}
class Invokable {
    public function __invoke(): string { return 'invoked'; }
}

function takeBase(Base $x) { echo 'base ', get_class($x), "\n"; }
function takeContract(Contract $x) { echo 'contract ', get_class($x), "\n"; }
function takeObject(object $x) { echo 'object ', get_class($x), "\n"; }
function takeArray(array $x) { echo 'array ', count($x), "\n"; }
function takeCallable(callable $x) { echo 'callable ', is_callable($x) ? 'yes' : 'no', "\n"; }
function takeIterable(iterable $x) { echo 'iterable ', is_array($x) ? 'array' : get_class($x), "\n"; }
function takeMixed(mixed $x) { echo 'mixed ', gettype($x), "\n"; }
function takeFalse(false $x) { echo 'false ', var_export($x, true), "\n"; }
function takeTrue(true $x) { echo 'true ', var_export($x, true), "\n"; }
function takeIntersection(Contract&Base $x) { echo 'intersection ', get_class($x), "\n"; }
function takeDnf((Contract&Base)|null $x) { echo 'dnf ', $x === null ? 'null' : get_class($x), "\n"; }

class Both extends Base implements Contract {}

takeBase(new Child());
takeContract(new ContractImpl());
takeObject(new stdClass());
takeArray([1, 2]);
takeCallable('strlen');
takeCallable(new Invokable());
takeIterable([1]);
takeIterable(new ArrayIterator([1]));
takeMixed(null);
takeFalse(false);
takeTrue(true);
takeIntersection(new Both());
takeDnf(new Both());
takeDnf(null);

foreach ([
    ['takeBase', new stdClass()], ['takeContract', new Base()], ['takeObject', 5],
    ['takeArray', 'x'], ['takeCallable', 'no_such_function_397'], ['takeIterable', 5],
    ['takeFalse', 0], ['takeTrue', 1], ['takeIntersection', new Base()],
] as [$fn, $value]) {
    try { $fn($value); } catch (TypeError $e) { echo strip($e->getMessage()), "\n"; }
}
try { takeDnf(5); } catch (TypeError $e) { echo strip($e->getMessage()), "\n"; }
