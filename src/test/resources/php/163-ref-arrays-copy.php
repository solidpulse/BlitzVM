<?php
$q = 1;
$v = 10;
$a = [&$q, $v];
$b = $a;
$b[0] = 9;
$b[1] = 99;
echo "shared_ref=$q,{$a[0]},{$b[0]} copied_val={$a[1]},{$b[1]}
";

function mutateCopy(array $copy) {
    $copy[0] = 55;
    $copy[1] = 66;
}
mutateCopy($a);
echo "after_fn=$q,{$a[0]},{$a[1]}
";

$o = new stdClass();
$o->v = 1;
$arrObj = array(&$o->v);
$arrObj[0] = 3;
echo "obj_elem={$o->v}
";

$kRef = 'init';
$map = ['key' => &$kRef, 'plain' => 'p'];
$mapCopy = $map;
$mapCopy['key'] = 'changed';
$mapCopy['plain'] = 'p2';
echo "map=$kRef,{$map['key']},{$map['plain']},{$mapCopy['plain']}
";

var_dump([&$q, 2]);
