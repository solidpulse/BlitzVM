<?php
require_once __DIR__ . '/boot.php';

echo "immutable=", $when->format(DATE_ATOM), "\n";
echo "zone=", $when->getTimezone()->getName(), "\n";
echo "mutable=", $mutable->format('Y-m-d H:i:s e'), "\n";
echo "interval=", $interval->d, " ", $interval->h, "\n";
$mutable->modify('+1 day');
echo "modified=", $mutable->format('Y-m-d H:i:s'), "\n";
$copy = clone $mutable;
$copy->modify('-1 day');
echo "copy=", $copy->format('Y-m-d H:i:s'), "\n";
echo "original=", $mutable->format('Y-m-d H:i:s'), "\n";
