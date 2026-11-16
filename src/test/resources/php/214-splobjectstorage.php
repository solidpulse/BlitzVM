<?php
error_reporting(E_ALL & ~E_DEPRECATED);
$a = (object)['name' => 'a'];
$b = (object)['name' => 'b'];
$s = new SplObjectStorage;
$s->attach($a, 'first');
$s[$b] = ['v' => 2];
echo count($s), ':', $s->contains($a) ? 'yes' : 'no', ':', $s[$a], "\n";
foreach ($s as $key => $object) { echo $key, ':', $object->name, ':', json_encode($s->getInfo()), '|'; $s->setInfo($object->name); }
echo "\n", json_encode($s[$b]), "\n";
$s->detach($a);
echo count($s), ':', isset($s[$a]) ? 'yes' : 'no', "\n";
$copy = clone $s;
unset($copy[$b]);
echo count($s), ':', count($copy), "\n";
class HashStorage extends SplObjectStorage { public function getHash($object): string { return $object->name; } }
$h = new HashStorage;
$h[(object)['name' => 'same']] = 1;
$h[(object)['name' => 'same']] = 2;
echo count($h), ':', $h[(object)['name' => 'same']], "\n";
