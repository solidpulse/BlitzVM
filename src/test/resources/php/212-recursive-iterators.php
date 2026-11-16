<?php
$data = ['a' => 1, 'b' => ['c' => 2, 'd' => [3]], 'e' => 4];
foreach ([RecursiveIteratorIterator::LEAVES_ONLY, RecursiveIteratorIterator::SELF_FIRST, RecursiveIteratorIterator::CHILD_FIRST] as $mode) {
    $it = new RecursiveIteratorIterator(new RecursiveArrayIterator($data), $mode);
    foreach ($it as $key => $value) { echo $it->getDepth(), ':', $key, ':', json_encode($value), '|'; }
    echo "\n";
}
$it = new RecursiveIteratorIterator(new RecursiveArrayIterator($data));
$it->setMaxDepth(1);
echo json_encode(iterator_to_array($it)), ':', $it->getMaxDepth(), "\n";
$r = new RecursiveArrayIterator(['a' => [1,2], 'b' => 3]);
foreach ($r as $key => $value) { echo $key, ':', $r->hasChildren() ? json_encode(iterator_to_array($r->getChildren())) : 'leaf', '|'; }
echo "\n";
$it = new IteratorIterator(new ArrayIterator(['x' => 7, 'y' => 8]));
echo json_encode(iterator_to_array($it)), ':', get_class($it->getInnerIterator()), "\n";
class RecursiveOverride extends RecursiveArrayIterator {
    public function current(): mixed { $value = parent::current(); return is_array($value) ? $value : $value * 2; }
    public function hasChildren(): bool { return parent::hasChildren(); }
}
echo json_encode(iterator_to_array(new RecursiveIteratorIterator(new RecursiveOverride([2, [3]])), false)), "\n";
