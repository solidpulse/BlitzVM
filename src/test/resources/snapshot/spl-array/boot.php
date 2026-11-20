<?php
$object = new stdClass();
$object->name = 'held';
$arrayObject = new ArrayObject(['a' => 1, 'object' => $object]);
$arrayIterator = new ArrayIterator([10, 20]);
