<?php
// A class referenced before its declaration: the failed site must resolve after it exists.
function useLater(): string
{
    return Later::name();
}

try {
    echo useLater(), "\n";
} catch (Error $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}

if (true) {
    final class Later
    {
        public static function name(): string
        {
            return 'later';
        }
    }
}

echo useLater(), "\n";

// class_alias after a failed resolution through the alias name.
function useAlias(): string
{
    return Alias::name();
}

try {
    echo useAlias(), "\n";
} catch (Error $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}

final class Original
{
    public static function name(): string
    {
        return 'original';
    }
}

class_alias(Original::class, 'Alias');
echo useAlias(), "\n";

// Several classes at one dynamic static-call site, more than the per-site cache keeps.
class A { public static function tag(): string { return 'A'; } }
final class B { public static function tag(): string { return 'B'; } }
final class C { public static function tag(): string { return 'C'; } }
final class D { public static function tag(): string { return 'D'; } }
final class E extends A { public static function tag(): string { return 'E+' . parent::tag(); } }

foreach ([A::class, B::class, C::class, D::class, E::class] as $class) {
    echo $class::tag(), "\n";
}

// A literal name that is already aliased keeps resolving to the aliased class.
echo Alias::name(), "\n";
echo Original::name(), "\n";
