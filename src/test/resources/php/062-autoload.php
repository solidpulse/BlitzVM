<?php

$loaded = new stdClass();
$loaded->trace = [];

$loader1 = function ($cls) use ($loaded) {
    $loaded->trace[] = 'L1:' . $cls;
    $path = __DIR__ . '/inc/' . $cls . '.php';
    if (file_exists($path)) {
        require_once $path;
    }
};
$loader2 = function ($cls) use ($loaded) {
    $loaded->trace[] = 'L2:' . $cls;
};

spl_autoload_register($loader1);
spl_autoload_register($loader2, true, true); // prepend loader2

echo 'count:' . count(spl_autoload_functions()), "\n";

// instanceof must NOT trigger autoloaders
$dummy = new stdClass();
var_dump($dummy instanceof UnloadedForInstanceof);

// class_exists with $autoload = false must NOT trigger autoloaders
var_dump(class_exists('AutoChild', false));

// extends + new triggers autoloading for both AutoChild and AutoParent
$c = new AutoChild('hello');
echo $c->name, '|', AutoParent::BASE, '|', AutoChild::baseTag(), "\n";

// class_exists with default $autoload = true triggers autoloading
var_dump(class_exists('AutoStatic'));
echo AutoStatic::VERSION, '|', AutoStatic::ping('ok'), "\n";

spl_autoload_unregister($loader2);
echo 'after_unreg:' . count(spl_autoload_functions()), "\n";
echo implode(',', $loaded->trace), "\n";
