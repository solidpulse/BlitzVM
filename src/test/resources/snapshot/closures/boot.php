<?php
class Greeter
{
    public $prefix;
    public $callback;

    public function __construct($prefix)
    {
        $this->prefix = $prefix;
    }

    public function make()
    {
        return function ($name) {
            return $this->prefix . $name;
        };
    }
}

function plain_function($value)
{
    return $value . '!';
}

$boundThis = new Greeter('hi ');
$bound = $boundThis->make();

$staticClosure = static function () {
    return 'static';
};

$count = 0;
$byRef = function () use (&$count) {
    return ++$count;
};

$firstClass = plain_function(...);

$closureStatic = function () {
    static $n = 0;
    return ++$n;
};

$holder = new Greeter('held ');
$holder->callback = $holder->make();
