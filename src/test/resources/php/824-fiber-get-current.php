<?php
// Fiber::getCurrent() is null when no fiber is running, which is every request in this runtime.
var_dump(Fiber::getCurrent());
try { Fiber::getCurrent(1); } catch (Throwable $e) { echo get_class($e), ': ', $e->getMessage(), "\n"; }
$r = new ReflectionClass('Fiber');
var_dump($r->isFinal());
