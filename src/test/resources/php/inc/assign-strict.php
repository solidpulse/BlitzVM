<?php
declare(strict_types=1);

// Included by fixture 409: a strict file assigning typed properties and by-ref parameters.
function assignStrict($object, $value) {
    $object->number = $value;
}

function coerceStrictByRef(int &$value) {
    $value++;
    return $value;
}
