<?php
$a = [1, 2];
$zero = &$a[0];
$one = &$a[1];

$reference = ReflectionReference::fromArrayElement($a, 0);
var_dump($reference instanceof ReflectionReference);
var_dump(strlen($reference->getId()));
var_dump($reference->getId() === ReflectionReference::fromArrayElement($a, 0)->getId());
var_dump($reference->getId() !== ReflectionReference::fromArrayElement($a, 1)->getId());
var_dump($reference === ReflectionReference::fromArrayElement($a, 0));

$b = [3];
var_dump(ReflectionReference::fromArrayElement($b, 0));

try {
    ReflectionReference::fromArrayElement($a, 5);
} catch (ReflectionException $e) {
    echo $e->getMessage(), "\n";
}

try {
    ReflectionReference::fromArrayElement('not an array', 0);
} catch (TypeError $e) {
    echo $e->getMessage(), "\n";
}

try {
    new ReflectionReference();
} catch (Error $e) {
    echo $e->getMessage(), "\n";
}
