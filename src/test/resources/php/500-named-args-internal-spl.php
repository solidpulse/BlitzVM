<?php
$ao = new ArrayObject(array: [3, 1, 2], flags: 0);
var_dump($ao->count());
var_dump($ao->offsetGet(key: 1));
$ao->offsetSet(key: 0, value: 9);
var_dump($ao->getArrayCopy());
var_dump($ao->getIteratorClass());
$ai = new ArrayIterator(array: ['a' => 1, 'b' => 2]);
var_dump($ai->offsetGet(key: 'b'));
var_dump($ai->count());
$sos = new SplObjectStorage();
$o1 = new stdClass();
$o2 = new stdClass();
$sos->attach(object: $o1, info: 'first');
$sos->attach(object: $o2);
var_dump($sos->count());
var_dump($sos->contains(object: $o1));
var_dump($sos->getInfo());
$sos->detach(object: $o2);
var_dump($sos->count());
var_dump([$ao, 'offsetGet'](key: 2));
$m = $ao->offsetGet(...);
var_dump($m(key: 1));
var_dump([$sos, 'contains'](object: $o1));
$attach = $sos->attach(...);
$o3 = new stdClass();
$attach(object: $o3, info: 'third');
var_dump($sos->count());
var_dump([$ai, 'offsetGet'](key: 'a'));
$aiGet = $ai->offsetGet(...);
var_dump($aiGet(key: 'b'));
foreach ([
    fn() => $ao->offsetGet(bogus: 0),
    fn() => $ao->offsetGet(0, key: 1),
    fn() => $sos->attach(o: $o1),
] as $call) {
    try { $call(); } catch (Throwable $e) { echo get_class($e), ': ', $e->getMessage(), "\n"; }
}
