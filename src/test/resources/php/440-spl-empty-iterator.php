<?php
$iterator = new EmptyIterator();

var_dump($iterator instanceof Iterator, $iterator->valid());

foreach (['current', 'key'] as $method) {
    try {
        $iterator->{$method}();
    } catch (BadMethodCallException $e) {
        echo $e->getMessage(), "\n";
    }
}

var_dump($iterator->next(), $iterator->rewind(), iterator_to_array($iterator), iterator_count($iterator));

try {
    $iterator->valid(1);
} catch (ArgumentCountError $e) {
    echo $e->getMessage(), "\n";
}

var_dump(get_class($iterator), get_parent_class($iterator));
