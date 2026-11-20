<?php
// Included by fixture 409: a weak file assigning typed properties and by-ref parameters.
function assignWeak($object, $value) {
    $object->number = $value;
}

function coerceWeakByRef(int &$value) {
    $value++;
    return $value;
}
