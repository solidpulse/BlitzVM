<?php
class WithToString {
    public function __toString(): string { return 'with'; }
}
class InheritsToString extends WithToString {}
class ExplicitStringable implements Stringable {
    public function __toString(): string { return 'explicit'; }
}

function takeStringable(Stringable $s): Stringable { return $s; }
function takeObject(object $o): object { return $o; }

$w = new WithToString();
$i = new InheritsToString();
$e = new ExplicitStringable();
echo get_class(takeStringable($w)), "\n";
echo get_class(takeStringable($i)), "\n";
echo get_class(takeStringable($e)), "\n";
var_dump($w instanceof Stringable, $i instanceof Stringable, $e instanceof Stringable);
var_dump($w instanceof InheritsToString);
var_dump($w instanceof ExplicitStringable);

interface HasLabel { public function label(): string; }
enum Plain { case Alpha; case Beta; }
enum Suit: string implements HasLabel {
    case Hearts = 'H';
    case Spades = 'S';
    public function label(): string { return $this->value . '!'; }
}

function takeUnit(UnitEnum $u): UnitEnum { return $u; }
function takeBacked(BackedEnum $b): BackedEnum { return $b; }
function takeLabel(HasLabel $h): HasLabel { return $h; }

var_dump(takeUnit(Plain::Alpha) === Plain::Alpha);
var_dump(takeBacked(Suit::Hearts) === Suit::Hearts);
var_dump(takeLabel(Suit::Spades) === Suit::Spades);
echo Suit::Hearts->label(), "\n";

var_dump(Plain::Alpha instanceof UnitEnum);
var_dump(Plain::Alpha instanceof BackedEnum);
var_dump(Plain::Alpha instanceof Plain);
var_dump(Plain::Beta instanceof Plain);
var_dump(Suit::Hearts instanceof UnitEnum);
var_dump(Suit::Hearts instanceof BackedEnum);
var_dump(Suit::Hearts instanceof HasLabel);
var_dump(Suit::Hearts instanceof Plain);
var_dump($w instanceof UnitEnum);

try { takeUnit($w); } catch (TypeError $e) { echo preg_replace('/, called in .*$/', '', $e->getMessage()), "\n"; }
try { takeBacked($w); } catch (TypeError $e) { echo preg_replace('/, called in .*$/', '', $e->getMessage()), "\n"; }
try { takeLabel(Plain::Alpha); } catch (TypeError $e) { echo preg_replace('/, called in .*$/', '', $e->getMessage()), "\n"; }
