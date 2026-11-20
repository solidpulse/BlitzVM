<?php
$pure = [1, 2, 3];
$pureNested = ['inner' => [4, 5]];

$first = new stdClass();
$first->name = 'one';
$second = new stdClass();
$second->name = 'two';
$objectArray = [$first, $second];

$shared = 'shared';
$refArray = ['x' => &$shared];
