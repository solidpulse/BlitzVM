<?php
// Weak-mode return type coercion and errors. The declaring file's mode decides.
function strip($s) {
    return str_replace(__DIR__ . '/', '', $s);
}

set_error_handler(function (int $errno, string $message) {
    echo 'DEPRECATED: ', $message, "\n";
    return true;
});

function returnsInt(): int { return "7"; }
function returnsFloat(): float { return 3; }
function returnsString(): string { return 12; }
function returnsBool(): bool { return "0"; }
function returnsIntFromFloat(): int { return 4.5; }
function returnsNone(): int { }
function returnsNullable(): ?int { return null; }
function returnsMixed(): mixed { return [1, 2]; }
function returnsNull(): null { return null; }

echo returnsInt(), "\n";
var_dump(returnsFloat());
echo returnsString(), "\n";
var_dump(returnsBool());
echo returnsIntFromFloat(), "\n";
try { returnsNone(); } catch (TypeError $e) { echo strip($e->getMessage()), "\n"; }
var_dump(returnsNullable());
var_dump(returnsMixed());
var_dump(returnsNull());

class Base {}
class Child extends Base {}
class Factory {
    public function selfType(): self { return $this; }
    public function staticType(): static { return $this; }
}
class SubFactory extends Factory {}

function returnsBase(): Base { return new Child(); }
function returnsWrongClass(): Base { return new stdClass(); }
echo get_class(returnsBase()), "\n";
try { returnsWrongClass(); } catch (TypeError $e) { echo strip($e->getMessage()), "\n"; }
echo get_class((new SubFactory())->selfType()), "\n";
echo get_class((new SubFactory())->staticType()), "\n";

$global = "9";
function &returnsReference(): int {
    global $global;
    return $global;
}
$reference = &returnsReference();
var_dump($global, $reference);

$closure = function (): string { return 12; };
echo $closure(), "\n";
$arrow = fn (): int => "13";
echo $arrow(), "\n";

function returnsVoid(): void { return; }
var_dump(returnsVoid());
function neverReturns(): never { }
try { neverReturns(); } catch (TypeError $e) { echo strip($e->getMessage()), "\n"; }

function returnsGenerator(): Generator { yield 1; return 5; }
$generator = returnsGenerator();
echo get_class($generator), "\n";
foreach ($generator as $value) { echo $value, "\n"; }
