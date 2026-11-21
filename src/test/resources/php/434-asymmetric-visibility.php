<?php

class Base
{
    public private(set) string $privateSet = "a";
    public protected(set) string $protectedSet = "b";
    private(set) string $implied = "c";
    protected(set) string $impliedProtected = "d";
    public private(set) static string $staticValue = "s";
    public readonly string $readonly;

    public function __construct()
    {
        $this->readonly = "r";
    }

    public function inside(): void
    {
        $this->privateSet = "A";
        $this->protectedSet = "B";
        $this->implied = "C";
        $this->impliedProtected = "D";
        self::$staticValue = "S";
    }

    public function readonlyFromInside(): void
    {
        $this->readonly = "x";
    }
}

class Child extends Base
{
    public function fromChild(): void
    {
        $this->protectedSet = "child";
    }

    public function privateFromChild(): void
    {
        $this->privateSet = "child";
    }
}

$base = new Base();
$base->inside();
echo $base->privateSet, $base->protectedSet, $base->implied, $base->impliedProtected, "\n";
echo Base::$staticValue, "\n";

function show(string $label, callable $fn): void
{
    try {
        $fn();
        echo $label, ": ok\n";
    } catch (Error $e) {
        echo $label, ": ", $e->getMessage(), "\n";
    }
}

show("global private", function () use ($base) {
    $base->privateSet = "x";
});
show("global protected", function () use ($base) {
    $base->protectedSet = "x";
});
show("global static", function () {
    Base::$staticValue = "x";
});
show("global readonly", function () use ($base) {
    $base->readonly = "x";
});
show("readonly from method", function () use ($base) {
    $base->readonlyFromInside();
});

$child = new Child();
show("child protected", function () use ($child) {
    $child->fromChild();
});
echo $child->protectedSet, "\n";
show("child private", function () use ($child) {
    $child->privateFromChild();
});

class Magic
{
    public protected(set) string $value = "v";

    public function __set(string $name, mixed $value): void
    {
        echo "magic set ", $name, "\n";
    }

    public function __get(string $name): mixed
    {
        echo "magic get ", $name, "\n";
        return "m";
    }
}

$magic = new Magic();
show("magic", function () use ($magic) {
    $magic->value = "x";
});

class Promoted
{
    public function __construct(
        public private(set) string $name,
        public protected(set) int $age,
        private(set) string $nickname = "nick",
    ) {
    }

    public function rename(): void
    {
        $this->name = "renamed";
        $this->age++;
        $this->nickname = "other";
    }
}

$promoted = new Promoted("first", 20);
echo $promoted->name, " ", $promoted->age, " ", $promoted->nickname, "\n";
$promoted->rename();
echo $promoted->name, " ", $promoted->age, " ", $promoted->nickname, "\n";
show("promoted write", function () use ($promoted) {
    $promoted->name = "x";
});

class ReadonlyAsymmetric
{
    public private(set) readonly int $id;

    public function __construct()
    {
        $this->id = 7;
    }
}

$ro = new ReadonlyAsymmetric();
echo $ro->id, "\n";
show("readonly asymmetric write", function () use ($ro) {
    $ro->id = 8;
});

foreach ([
    Base::class => ["privateSet", "protectedSet", "implied", "impliedProtected", "readonly", "staticValue"],
] as $class => $names) {
    foreach ($names as $name) {
        $property = new ReflectionProperty($class, $name);
        echo $name, ": mods=", $property->getModifiers(),
            " privateSet=", (int) $property->isPrivateSet(),
            " protectedSet=", (int) $property->isProtectedSet(),
            " readonly=", (int) $property->isReadOnly(), "\n";
    }
}
