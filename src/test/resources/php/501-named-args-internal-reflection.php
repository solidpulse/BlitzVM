<?php
$e = new Exception(message: 'boom', code: 7);
var_dump($e->getMessage());
var_dump($e->getCode());
$prev = new RuntimeException(message: 'inner', code: 3);
$outer = new Exception(message: 'outer', code: 4, previous: $prev);
var_dump($outer->getPrevious()->getMessage());
$rc = new ReflectionClass(objectOrClass: 'DateTime');
var_dump($rc->getName());
class Widget { public $made; function __construct($a = 1, $b = 2) { $this->made = "$a-$b"; } }
$widgetClass = new ReflectionClass(objectOrClass: 'Widget');
var_dump($widgetClass->newInstance(b: 5, a: 1)->made);
var_dump($widgetClass->newInstanceArgs(['b' => 5, 'a' => 1])->made);
var_dump($rc->newInstance(datetime: '2020-01-02 03:04:05', timezone: new DateTimeZone('UTC'))->format('Y-m-d H:i:s'));
var_dump($rc->hasMethod(name: 'format'));
var_dump($rc->getMethod(name: 'format')->getName());
$rm = new ReflectionMethod(objectOrMethod: 'DateTime', method: 'format');
var_dump($rm->getName());
var_dump($rm->invoke(object: new DateTime('2022-05-06'), format: 'Y'));
var_dump($rm->invokeArgs(object: new DateTime('2022-05-06'), args: ['m']));
var_dump([$rc, 'hasMethod'](name: 'modify'));
$gm = $rc->getMethod(...);
var_dump($gm(name: 'setDate')->getName());
var_dump([$rm, 'invoke'](object: new DateTime('2023-07-08'), format: 'd'));
$inv = $rm->invoke(...);
var_dump($inv(object: new DateTime('2023-07-08'), format: 'Y-m'));
$getMessage = $e->getMessage(...);
var_dump($getMessage());
var_dump([$e, 'getCode']());
try { $getMessage(oops: 1); } catch (Throwable $ex) { echo get_class($ex), ': ', $ex->getMessage(), "\n"; }
foreach ([
    fn() => $e->getMessage(oops: 1),
    fn() => $rc->getMethod(method: 'format'),
    fn() => $rc->getMethod('format', name: 'getName'),
    fn() => $rm->invoke(object: new DateTime(), bogus: 1),
    fn() => $rc->newInstance(nope: 1),
] as $call) {
    try { $call(); } catch (Throwable $ex) { echo get_class($ex), ': ', $ex->getMessage(), "\n"; }
}
