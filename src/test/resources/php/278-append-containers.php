<?php
function containers() {
    $s = 'a'; $s .= 'b'; $a = [$s, ['s' => $s]]; $b = $a;
    $a[0] .= 'c'; $a[1]['s'] .= 'd'; $s .= 'e';
    echo json_encode($a), '|', json_encode($b), '|', $s, "\n";
    $object = new stdClass(); $object->s = $s; $clone = clone $object;
    $object->s .= 'f'; echo $object->s, '|', $clone->s, "\n";
    echo json_encode($object), '|', json_encode((array) $object), '|', json_encode(get_object_vars($object)), "\n";
    foreach ($object as $name => $value) { echo $name, '=', $value, "\n"; }
    echo serialize((array) $object), "\n";
    $a = ['x']; $r =& $a[0]; $r .= 'y'; $b = $a; $r .= 'z';
    echo json_encode($a), '|', json_encode($b), "\n";
}
containers();
