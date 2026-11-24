<?php
// ArrayObject and ArrayIterator property access: ARRAY_AS_PROPS turns reads, writes,
// appends, isset, unset, ??= and ++ on undeclared names into array operations, while
// declared properties keep winning over the array.
$ao = new ArrayObject(['a' => 1, 'k' => 2], ArrayObject::ARRAY_AS_PROPS);
var_dump($ao->a);
var_dump(isset($ao->k));
var_dump($ao->missing ?? 'default');
$ao->b = 3;
$ao->list[] = 4;
$ao->list[] = 5;
var_dump($ao->getArrayCopy());
var_dump(isset($ao->b), isset($ao->nope));
unset($ao->b);
var_dump(isset($ao->b), $ao->getArrayCopy());
$ao->n ??= 'made';
var_dump($ao->n);
$ao->c = 10;
$ao->c++;
var_dump($ao->c);
$ao->d = 10;
var_dump(++$ao->d);
$ao->d -= 3;
var_dump($ao->d);
unset($ao['k']);
var_dump($ao->getArrayCopy());

// Without ARRAY_AS_PROPS the array is not reachable through properties.
$plain = new ArrayObject(['a' => 1]);
$plain->a2 = 7;
var_dump($plain->getArrayCopy());
var_dump($plain['a'], isset($plain->a));

class MyArrayObject extends ArrayObject
{
}

$sub = new MyArrayObject(['x' => 1], ArrayObject::ARRAY_AS_PROPS);
var_dump($sub->x);
$sub->y = 2;
$sub->y++;
var_dump($sub->getArrayCopy());

class DeclaredArrayObject extends ArrayObject
{
    public int $declared = 5;
}

$declared = new DeclaredArrayObject(['declared' => 99, 'other' => 1], ArrayObject::ARRAY_AS_PROPS);
var_dump($declared->declared);
$declared->declared = 6;
$declared->other++;
var_dump($declared->declared, $declared->other);
var_dump($declared['declared'], $declared['other']);

$it = new ArrayIterator(['q' => 1], ArrayIterator::ARRAY_AS_PROPS);
var_dump($it->q);
$it->z = 9;
$it->z += 2;
var_dump($it['z']);
unset($it->q);
var_dump(isset($it->q), $it['q'] ?? 'gone');
