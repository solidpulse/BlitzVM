<?php
// from()/tryFrom() on literal-backed enums (indexed) and expression-backed enums (scanned).
set_error_handler(function ($no, $msg) { echo "warning: $msg\n"; return true; });

enum Zone: string {
    case UTC = 'UTC';
    case Paris = 'Europe/Paris';
    case Kolkata = 'Asia/Kolkata';
    case Empty = '';
    case Zero = '0';
}

enum Level: int {
    case Low = 1;
    case Mid = 5;
    case High = -7;
    case Max = PHP_INT_MAX;
}

const BASE = 'b-';
enum Expr: string {
    case A = BASE . 'a';
    case B = 'b-' . 'b';
    case C = 'plain';
}

class Holder { const N = 40; }
enum ExprInt: int {
    case X = Holder::N + 2;
    case Y = 7;
}

class Str { function __toString(): string { return 'Europe/Paris'; } }

var_dump(Zone::from('UTC') === Zone::UTC);
var_dump(Zone::tryFrom('Asia/Kolkata') === Zone::Kolkata);
var_dump(Zone::tryFrom('asia/kolkata'));
var_dump(Zone::tryFrom('') === Zone::Empty);
var_dump(Zone::tryFrom('0') === Zone::Zero);
var_dump(Zone::tryFrom(0) === Zone::Zero);
var_dump(Zone::tryFrom(new Str()) === Zone::Paris);
var_dump(Level::from(5) === Level::Mid);
var_dump(Level::tryFrom(-7) === Level::High);
var_dump(Level::tryFrom(PHP_INT_MAX) === Level::Max);
var_dump(Level::tryFrom(2));
var_dump(Level::tryFrom('5') === Level::Mid);
var_dump(Level::tryFrom(5.0) === Level::Mid);
var_dump(Level::tryFrom(true) === Level::Low);
var_dump(Expr::from('b-a') === Expr::A);
var_dump(Expr::tryFrom('b-b') === Expr::B);
var_dump(Expr::tryFrom('plain') === Expr::C);
var_dump(Expr::tryFrom('a'));
var_dump(ExprInt::from(42) === ExprInt::X);
var_dump(ExprInt::tryFrom(7) === ExprInt::Y);
var_dump(ExprInt::tryFrom(40));

foreach ([fn() => Zone::from('nowhere'), fn() => Level::from(3), fn() => Expr::from('zz'),
          fn() => Level::from('abc'), fn() => Zone::from([]), fn() => Level::from(1.5)] as $f) {
    try {
        var_dump($f());
    } catch (Throwable $e) {
        echo get_class($e), ': ', $e->getMessage(), "\n";
    }
}

$names = array_map(fn($c) => $c->name, array_filter(Zone::cases(), fn($c) => Zone::tryFrom($c->value) === $c));
echo implode(',', $names), "\n";
echo count(array_filter(range(-10, 10), fn($i) => Level::tryFrom($i) !== null)), "\n";
