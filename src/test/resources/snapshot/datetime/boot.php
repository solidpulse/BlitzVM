<?php
$zone = new DateTimeZone('Europe/Paris');
$when = new DateTimeImmutable('2020-01-02T03:04:05+00:00');
$when = $when->setTimezone($zone);
$mutable = new DateTime('2021-02-03 04:05:06', $zone);
$interval = new DateInterval('P1DT2H');
