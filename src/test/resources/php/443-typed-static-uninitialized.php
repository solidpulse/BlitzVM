<?php
class LazyClosure
{
    private static Closure $closure;

    public static function create(): Closure
    {
        var_dump(isset(self::$closure));
        var_dump(self::$closure ?? 'default');

        self::$closure ??= static fn (): int => 42;

        var_dump(isset(self::$closure));

        return self::$closure;
    }
}

var_dump((LazyClosure::create())());

class TypedStatic
{
    public static int $number;
}

var_dump(isset(TypedStatic::$number), TypedStatic::$number ?? 'unset');

try {
    var_dump(TypedStatic::$number);
} catch (Error $e) {
    echo $e->getMessage(), "\n";
}

TypedStatic::$number ??= 7;
var_dump(TypedStatic::$number);
