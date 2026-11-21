<?php
$array = new SplFixedArray(3);
var_dump($array instanceof IteratorAggregate, $array instanceof ArrayAccess, $array->getSize(), $array->count());
$array[0] = 'a';
$array[2] = 'c';
var_dump($array[0], $array->toArray(), isset($array[1]), isset($array[0]));
unset($array[0]);
$array->setSize(2);
var_dump($array->toArray());

$copy = SplFixedArray::fromArray(['x', 'y']);
var_dump($copy->toArray(), json_encode($copy));

try {
    $array[5] = 'out';
} catch (OutOfBoundsException $e) {
    echo $e->getMessage(), "\n";
}

try {
    $array[5];
} catch (OutOfBoundsException $e) {
    echo $e->getMessage(), "\n";
}

$queue = new SplQueue();
$queue->enqueue('a');
$queue->enqueue('b');
var_dump($queue->count(), $queue->bottom(), $queue->top());
var_dump($queue->dequeue(), $queue->dequeue(), $queue->isEmpty());

$stack = new SplStack();
$stack->push(1);
$stack->push(2);
var_dump($stack->top(), $stack->pop(), $stack->count());

$list = new SplDoublyLinkedList();
$list->push('a');
$list->push('b');
$list->unshift('z');
$list->add(1, 'm');
var_dump($list->count(), $list->bottom(), $list->top());
foreach ($list as $value) {
    echo 'list ', $value, "\n";
}
$list->setIteratorMode(SplDoublyLinkedList::IT_MODE_LIFO);
foreach ($list as $value) {
    echo 'lifo ', $value, "\n";
}

$append = new AppendIterator();
$append->append(new ArrayIterator([1, 2]));
$append->append(new ArrayIterator(['a' => 'x', 'b' => 'y']));
foreach ($append as $key => $value) {
    echo 'append ', $key, '=', $value, "\n";
}
var_dump($append->getIteratorIndex());
