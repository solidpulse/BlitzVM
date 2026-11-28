<?php
$d = new DateTime('2020-01-02 03:04:05', new DateTimeZone('UTC'));
var_dump($d->format(format: 'Y-m-d H:i:s'));
var_dump($d->setDate(year: 2021, month: 3, day: 4) instanceof DateTime);
var_dump($d->format('Y-m-d'));
var_dump($d->setTime(hour: 5, minute: 6) instanceof DateTime);
var_dump($d->format('H:i:s'));
$i = new DateTimeImmutable('2021-05-06 07:08:09', new DateTimeZone('UTC'));
var_dump($i->format(format: 'D, d M Y'));
var_dump($i->modify(modifier: '+1 day')->format('Y-m-d'));
var_dump($i->diff(targetObject: new DateTimeImmutable('2021-05-08', new DateTimeZone('UTC')), absolute: true)->days);
$z = new DateTimeZone(timezone: 'Europe/Paris');
var_dump($z->getName());
var_dump($z->getOffset(datetime: new DateTime('2020-06-01', new DateTimeZone('UTC'))));
var_dump(count($z->getTransitions()) > 100);
$iv = new DateInterval(duration: 'P1DT2H');
var_dump($iv->format('%d %h'));
var_dump(DateTime::createFromFormat(format: 'Y-m-d', datetime: '2020-01-02')->format('Y-m-d'));
$fmt = $d->format(...);
var_dump($fmt(format: 'd/m/Y'));
var_dump([$d, 'format'](format: 'm'));
$zoneName = $z->getName(...);
var_dump($zoneName());
var_dump([$iv, 'format'](format: '%h'));
$immutableFormat = $i->format(...);
var_dump($immutableFormat(format: 'Y'));
var_dump([$z, 'getOffset'](datetime: new DateTime('2020-12-01', new DateTimeZone('UTC'))));
foreach ([
    fn() => $d->format(bogus: 'Y'),
    fn() => $d->format(FORMAT: 'Y'),
    fn() => $d->format('Y', format: 'm'),
] as $call) {
    try { $call(); } catch (Throwable $e) { echo get_class($e), ': ', $e->getMessage(), "\n"; }
}
