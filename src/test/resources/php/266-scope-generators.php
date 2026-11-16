<?php
function scoped_generator($value) {
    extract(['delta' => 3]);
    include __DIR__ . '/inc/scope-generator.php';
    yield $value;
    $delta++;
    include __DIR__ . '/inc/scope-generator.php';
    yield $from_file;
    $name = 'delta'; yield $$name;
    return $value;
}
$a = scoped_generator(1); $b = scoped_generator(10);
echo $a->current(), ':', $b->current(), "\n";
$a->next(); $b->next();
echo $a->current(), ':', $b->current(), "\n";
$a->next(); echo $a->current(), "\n";
$a->next(); echo $a->getReturn(), "\n";
