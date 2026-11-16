<?php
$a = new ArrayIterator(['b' => 3, 'a' => 1, 5 => 2]);
echo json_encode(iterator_to_array($a)), ':', count($a), "\n";
$a['b'] = 4;
$a[] = 7;
$a->append(9);
unset($a[5]);
echo json_encode($a->getArrayCopy()), "\n";
$a->asort(); echo json_encode($a->getArrayCopy()), "\n";
$a->ksort(); echo json_encode($a->getArrayCopy()), "\n";
$a->uasort(fn($a,$b) => $b <=> $a); echo json_encode($a->getArrayCopy()), "\n";
$a->uksort(fn($a,$b) => strcmp((string)$b,(string)$a)); echo json_encode($a->getArrayCopy()), "\n";
$a->seek(1); echo $a->key(), ':', $a->current(), "\n";
var_dump($a instanceof SeekableIterator, $a instanceof Countable, $a instanceof ArrayAccess, $a->getFlags());
class CustomArrayIterator extends ArrayIterator {
    public function current(): mixed { return parent::current() * 10; }
    public function offsetGet($k): mixed { return parent::offsetGet($k) + 100; }
}
$c = new CustomArrayIterator([2,3]);
echo json_encode(iterator_to_array($c)), ':', $c[0], "\n";
$r = new ArrayIterator(['nested' => ['v' => 1], 'n' => 2]);
$r['nested']['v'] = 8;
$r['n']++;
echo json_encode($r->getArrayCopy()), "\n";
$copy = clone $r;
$copy['n'] = 20;
echo json_encode($r->getArrayCopy()), ':', json_encode($copy->getArrayCopy()), "\n";
$o = (object)['x' => 1];
$it = new ArrayIterator($o);
$it['x'] = 8;
$o->y = 2;
echo json_encode(iterator_to_array($it)), ':', $o->x, "\n";
error_reporting(E_ALL & ~E_DEPRECATED);
$serial = new ArrayIterator(['a' => 3]);
$encoded = $serial->serialize();
echo $encoded, "\n";
$serial->unserialize($encoded);
echo json_encode($serial->getArrayCopy()), "\n";
$natural = new ArrayIterator(['item20', 'item3', 'item1']);
$natural->asort(SORT_NATURAL);
echo json_encode($natural->getArrayCopy()), "\n";
