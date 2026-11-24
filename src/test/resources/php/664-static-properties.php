<?php
// Static properties: inheritance, redeclaration, references, static::$x, typed, ++.
class Base {
    public static int $count = 0;
    public static $name = 'base';
    public static ?int $maybe = null;
    public static $word = 'a';
    public static $ratio = 1.5;
    public static int $typed = 5;
    protected static $secret = 's';
    public static function bump(): int {
        return ++static::$count;
    }
    public static function readSecret(): string {
        return static::$secret;
    }
}
class Child extends Base {
    public static $name = 'child';
}
class GrandChild extends Child {
    public static $name = 'grand';
}
class Redeclare extends Base {
    public static int $count = 100;
}

Base::$count++;
Base::$count++;
echo Base::$count, " ", Child::$count, "\n";
echo Base::$name, " ", Child::$name, " ", GrandChild::$name, "\n";
echo Base::bump(), " ", Base::$count, "\n";
echo Child::readSecret(), "\n";
echo Redeclare::$count, "\n";
Redeclare::$count += 5;
echo Redeclare::$count, " ", Base::$count, "\n";

$ref = &Base::$name;
$ref = 'changed';
echo Base::$name, " ", Child::$name, "\n";

Base::$word++;
echo Base::$word, "\n";
Base::$word = 'z';
Base::$word++;
echo Base::$word, "\n";

Base::$maybe++;
var_dump(Base::$maybe);
Base::$maybe++;
var_dump(Base::$maybe);

Base::$ratio++;
var_dump(Base::$ratio);

Base::$typed = PHP_INT_MAX;
try {
    Base::$typed++;
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
var_dump(Base::$typed);
Base::$typed = PHP_INT_MIN;
try {
    Base::$typed--;
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
var_dump(Base::$typed);
Base::$typed = 1;
echo Base::$typed++, " ", Base::$typed, "\n";

try {
    echo Base::$missing;
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
try {
    Base::$secret;
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}

function counter() {
    static $n = 0;
    return ++$n;
}
echo counter(), counter(), counter(), "\n";
