<?php
// Enums: match on cases, cases(), and from()/tryFrom() with both backing types.
enum Suit: string {
    case Hearts = 'h';
    case Spades = 's';
    public function label(): string {
        return match ($this) {
            Suit::Hearts => 'Hearts',
            Suit::Spades => 'Spades',
        };
    }
}

enum Level: int {
    case Low = 1;
    case High = -7;
    case Zero = 0;
}

var_dump(Suit::Hearts->label());
var_dump(Suit::Spades->label());
var_dump(Suit::Hearts === Suit::Hearts);

$names = array_map(fn($c) => $c->name, Suit::cases());
echo implode(',', $names), "\n";
echo implode(',', array_map(fn($c) => $c->value, Suit::cases())), "\n";
echo implode(',', array_map(fn($c) => $c->value, Level::cases())), "\n";

var_dump(Suit::from('h') === Suit::Hearts);
var_dump(Suit::tryFrom('s') === Suit::Spades);
var_dump(Suit::tryFrom('x'));
var_dump(Level::from(1) === Level::Low);
var_dump(Level::tryFrom(-7) === Level::High);
var_dump(Level::tryFrom(0) === Level::Zero);
var_dump(Level::tryFrom(2));
var_dump(Level::tryFrom('1') === Level::Low);
var_dump(Suit::tryFrom('') === null);

foreach ([fn() => Suit::from('nope'), fn() => Level::from(9), fn() => Level::from('x')] as $f) {
    try {
        $f();
    } catch (Throwable $e) {
        echo get_class($e), ': ', $e->getMessage(), "\n";
    }
}

enum Plain {
    case A;
    case B;
}
var_dump(Plain::A === Plain::A);
var_dump(Plain::A === Plain::B);
echo implode(',', array_map(fn($c) => $c->name, Plain::cases())), "\n";
