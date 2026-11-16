<?php
$byValue = function () use ($x) { return $x; };
$byRef = function () use (&$x) { return ++$x; };
$arrow = fn() => $x + 10;
