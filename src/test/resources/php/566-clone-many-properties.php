<?php
// clone: many properties, dynamic properties, arrays, nested objects and __clone.

class ManyProps
{
    public int $p1 = 1;
    public int $p2 = 2;
    public int $p3 = 3;
    public int $p4 = 4;
    public int $p5 = 5;
    public int $p6 = 6;
    public int $p7 = 7;
    public int $p8 = 8;
    public int $p9 = 9;
    public array $list = ['a'];
    public object $inner;
    protected ?ManyProps $self = null;
    private string $secret = 'secret';
    public static int $copies = 0;

    public function __construct()
    {
        $this->inner = new stdClass();
        $this->inner->value = 1;
        $this->self = $this;
    }

    public function __clone()
    {
        self::$copies++;
        $this->p1 = 100;
        $this->secret .= '!';
    }

    public function secret(): string
    {
        return $this->secret;
    }

    public function self(): ?ManyProps
    {
        return $this->self;
    }

    public function setSecret(string $secret): void
    {
        $this->secret = $secret;
    }
}

$original = new ManyProps();
$original->dynamicOne = 'one';
$original->dynamicTwo = [1, 2];
$original->p2 = 22;
$original->list[] = 'b';

$copy = clone $original;
var_dump($copy !== $original, $copy->p1, $copy->p2, $copy->p3, $copy->p9, $copy->secret());
var_dump($copy->dynamicOne, $copy->dynamicTwo, $copy->list);
var_dump($copy->inner === $original->inner, $copy->self() === $original);
var_dump(ManyProps::$copies);

$copy->list[] = 'c';
var_dump($original->list, $copy->list);

$copy->inner->value = 2;
var_dump($original->inner->value);

$copy->setSecret('changed');
var_dump($original->secret(), $copy->secret());

$deep = new ManyProps();
$deepCopy = clone $deep;
var_dump($deepCopy->p1, ManyProps::$copies);

function cloneWith(ManyProps $object): ManyProps
{
    return clone($object, ['p3' => 33, 'dynamicOne' => 'set']);
}

$with = cloneWith($original);
var_dump($with->p3, $with->p1, $with->dynamicOne, $with->secret());

$empty = new stdClass();
$empty->a = 1;
$emptyCopy = clone $empty;
var_dump($emptyCopy !== $empty, $emptyCopy->a);

$nested = new stdClass();
$nested->child = new stdClass();
$nested->child->value = 'x';
$nestedCopy = clone $nested;
$nestedCopy->child->value = 'y';
var_dump($nested->child->value);

// Objects with a reference-valued property keep the alias after clone.
$aliased = new stdClass();
$aliased->cell = &$original->p4;
$aliasedCopy = clone $aliased;
$aliasedCopy->cell = 44;
var_dump($original->p4, $aliased->cell, $aliasedCopy->cell);
