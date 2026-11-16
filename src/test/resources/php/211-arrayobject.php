<?php
$a = new ArrayObject(['a' => 1, 'b' => 2]);
echo count($a), ':', json_encode(iterator_to_array($a)), ':', json_encode($a), "\n";
$it = $a->getIterator();
$a['a'] = 9;
echo json_encode(iterator_to_array($it)), "\n";
echo json_encode($a->exchangeArray(['x' => 4])), ':', json_encode($a->getArrayCopy()), "\n";
echo json_encode(iterator_to_array($it)), "\n";
$a->append(7);
echo json_encode((array)$a), "\n";
$a->setFlags(ArrayObject::ARRAY_AS_PROPS);
$a->z = 11;
echo $a->x, ':', $a->z, ':', $a['z'], ':', json_encode($a->getArrayCopy()), "\n";
var_dump(isset($a->x));
unset($a->x);
echo json_encode($a->getArrayCopy()), "\n";
$a->setFlags(ArrayObject::STD_PROP_LIST);
echo json_encode((array)$a), "\n";
class ArrayObjectIterator extends ArrayIterator { public function current(): mixed { return parent::current() + 10; } }
$a = new ArrayObject([1,2], 0, ArrayObjectIterator::class);
echo $a->getIteratorClass(), ':', json_encode(iterator_to_array($a)), "\n";
$a = new ArrayObject(['v' => 3]);
ob_start(); var_dump($a); $dump = ob_get_clean();
echo preg_replace('/#\d+/', '#id', $dump);
$a['null'] = null;
var_dump(isset($a['null']), $a->offsetExists('null'), $a['null'] ?? 'fallback');
class PropertyArrayObject extends ArrayObject {
    public function offsetGet($key): mixed { return parent::offsetGet($key) + 10; }
    public function offsetSet($key, $value): void { parent::offsetSet($key, $value * 2); }
}
$a = new PropertyArrayObject(['x' => 1], ArrayObject::ARRAY_AS_PROPS);
echo $a->x, '|';
$a->x = 4;
echo $a->x, ':', json_encode($a->getArrayCopy()), "\n";
$v = 5;
$a = new ArrayObject;
$a['ref'] = &$v;
$v++;
$a['ref']++;
echo $v, ':', $a['ref'], "\n";
$source = new ArrayObject(['x' => 1]);
$iterator = new ArrayIterator($source);
$source['x'] = 3;
$iterator['x'] = 8;
echo $source['x'], ':', $iterator['x'], "\n";
