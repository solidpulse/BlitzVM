<?php
foreach (['Traversable', 'Iterator', 'IteratorAggregate', 'ArrayAccess', 'Countable', 'JsonSerializable', 'Stringable', 'Serializable'] as $name) {
    echo $name, ':', interface_exists($name) ? 'yes' : 'no', '\n';
}
class PrintableValue {
    public function __toString(): string { return 'value'; }
}
class CountedValue implements Countable {
    public function count(): int { return 7; }
}
class ChildCountedValue extends CountedValue {}
$p = new PrintableValue;
$c = new ChildCountedValue;
var_dump($p instanceof Stringable, (string)$p, count($c), sizeof($c, COUNT_RECURSIVE));
var_dump(is_countable([]), is_countable($c), is_countable($p), is_iterable([]), is_iterable($c));
echo json_encode(class_implements($c)), '\n', json_encode(class_parents($c)), '\n';
try { count($p); } catch (TypeError $e) { echo $e->getMessage(), '\n'; }
try { array_keys($c); } catch (TypeError $e) { echo $e->getMessage(), '\n'; }
