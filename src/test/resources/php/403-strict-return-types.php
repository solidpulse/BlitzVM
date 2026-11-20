<?php
declare(strict_types=1);

// Strict-mode returns, by-reference returns and generator types.
function strip($s) {
    return str_replace(__DIR__ . '/', '', $s);
}

function strictInt(): int { return "7"; }
function strictFloat(): float { return 3; }
function strictString(): string { return 12; }
function strictNullable(): ?int { return null; }
function strictFalse(): false { return false; }
function strictTrue(): true { return true; }
function strictArray(): array { return []; }
function strictIterable(): iterable { yield 1; }
function strictTraversable(): Traversable { yield 1; }
function strictGenerator(): Generator { yield 1; return 2; }
function strictMixed(): mixed { return "anything"; }

var_dump(strictFloat());
var_dump(strictNullable());
var_dump(strictFalse());
var_dump(strictTrue());
var_dump(strictArray());
var_dump(strictMixed());
try { strictInt(); } catch (TypeError $e) { echo strip($e->getMessage()), "\n"; }
try { strictString(); } catch (TypeError $e) { echo strip($e->getMessage()), "\n"; }
echo get_class(strictIterable()), "\n";
echo get_class(strictTraversable()), "\n";
echo get_class(strictGenerator()), "\n";

$global = "5";
function &strictReference(): int {
    global $global;
    return $global;
}
$reference = null;
try { $reference = &strictReference(); } catch (TypeError $e) { echo strip($e->getMessage()), "\n"; }
var_dump($global, $reference);

class Factory {
    public function make(): static { return $this; }
    public function copy(): self { return new self(); }
}
$factory = new Factory();
echo get_class($factory->make()), "\n";
echo get_class($factory->copy()), "\n";
