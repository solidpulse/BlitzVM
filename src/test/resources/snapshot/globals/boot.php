<?php
$sawPreGlobal = $preGlobal;
$sawFlag = ENTRY_FLAG;

$shared = 'boot';
$copy = &$shared;

function next_counter()
{
    static $n = 0;
    return ++$n;
}

function read_global()
{
    global $shared;
    return $shared;
}

class Counter
{
    public static $count = 0;
}

Counter::$count = 5;

define('BASE', 7);

class Config
{
    const VALUE = BASE * 6;
}

// Evaluate the lazy constant during boot so the cached value is part of the snapshot.
$configValue = Config::VALUE;

class Typed
{
    public string $name;
    public int $unset;
}

$typed = new Typed();
$typed->name = 'typed';
$typedRef = &$typed->name;
