<?php
class Stored {
    public $name = 'stored';
}

$arrayObject = new ArrayObject([1, 2, 3], ArrayObject::ARRAY_AS_PROPS);
$arrayObject->extra = 'value';
$serialized = serialize($arrayObject);
echo $serialized, "\n";
$restored = unserialize($serialized);
echo get_class($restored), ' ', count($restored), ' ', $restored['extra'], ' ', $restored->getFlags(), "\n";
echo serialize($restored), "\n";
var_dump($restored->getArrayCopy());

$iterator = new ArrayIterator(['a' => 1, 'b' => 2]);
$serialized = serialize($iterator);
echo $serialized, "\n";
$restored = unserialize($serialized);
echo get_class($restored), ' ', count($restored), "\n";
foreach ($restored as $key => $value) {
    echo $key, '=', $value, "\n";
}
echo serialize($restored), "\n";

$storage = new SplObjectStorage();
$stored = new Stored();
$storage[$stored] = ['info' => 3];
$storage->label = 'storage';
$serialized = serialize($storage);
echo $serialized, "\n";
$restored = unserialize($serialized);
echo get_class($restored), ' ', count($restored), ' ', $restored->label, "\n";
foreach ($restored as $key) {
    echo get_class($key), ' ', $restored[$key]['info'], "\n";
}
echo serialize($restored), "\n";

$date = new DateTime('2020-01-02 03:04:05.123456', new DateTimeZone('Europe/London'));
$serialized = serialize($date);
echo $serialized, "\n";
$restored = unserialize($serialized);
echo ($restored instanceof DateTime) ? 'DateTime' : 'other', ' ',
    $restored->format('Y-m-d H:i:s.u'), ' ', $restored->getTimezone()->getName(), "\n";
echo serialize($restored), "\n";

$immutable = new DateTimeImmutable('2021-02-03 04:05:06', new DateTimeZone('UTC'));
$serialized = serialize($immutable);
echo $serialized, "\n";
$restored = unserialize($serialized);
echo ($restored instanceof DateTimeImmutable) ? 'DateTimeImmutable' : 'other', ' ',
    $restored->format('c'), "\n";
echo serialize($restored), "\n";

$zone = new DateTimeZone('Europe/Paris');
$serialized = serialize($zone);
echo $serialized, "\n";
$restored = unserialize($serialized);
echo get_class($restored), ' ', $restored->getName(), "\n";
echo serialize($restored), "\n";

$interval = new DateInterval('P1Y2M3DT4H5M6S');
$serialized = serialize($interval);
echo $serialized, "\n";
$restored = unserialize($serialized);
echo get_class($restored), ' ', $restored->format('%y-%m-%d %h:%i:%s'), "\n";
echo serialize($restored), "\n";

$offsetZone = new DateTimeZone('+02:00');
echo serialize($offsetZone), "\n";
echo serialize(unserialize(serialize($offsetZone))), "\n";

$dynamic = new DateTime('2022-01-01 00:00:00', new DateTimeZone('UTC'));
$dynamic->tag = 'extra';
$serialized = serialize($dynamic);
echo $serialized, "\n";
$restored = unserialize($serialized);
echo $restored->tag, ' ', $restored->format('Y-m-d'), "\n";
echo serialize($restored), "\n";

$fromString = DateInterval::createFromDateString('2 days');
$fromString->tag = 'interval';
$serialized = serialize($fromString);
echo $serialized, "\n";
$restored = unserialize($serialized);
echo $restored->tag, ' ', $restored->format('%d'), "\n";
echo serialize($restored), "\n";
