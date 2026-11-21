<?php
set_error_handler(function ($n, $s) { echo "warning:$s\n"; return true; });

$autoloaded = [];

spl_autoload_register(function (string $class) use (&$autoloaded): void {
    $autoloaded[] = $class;

    match ($class) {
        'AutoloadParent' => eval('class AutoloadParent {}'),
        'AutoloadChild' => eval('class AutoloadChild extends AutoloadParent implements AutoloadInterface { public $value; public function method(): void {} }'),
        'AutoloadInterface' => eval('interface AutoloadInterface {}'),
        'AutoloadImplementation' => eval('class AutoloadImplementation implements AutoloadInterface {}'),
        default => null,
    };
});

var_dump(is_a('AutoloadChild', 'AutoloadInterface', true));
var_dump($autoloaded);

$autoloaded = [];
var_dump(is_subclass_of('AutoloadChild', 'AutoloadParent'));
var_dump($autoloaded);

$autoloaded = [];
var_dump(method_exists('AutoloadChild', 'method'));
var_dump($autoloaded);

$autoloaded = [];
var_dump(property_exists('AutoloadChild', 'value'));
var_dump($autoloaded);

$autoloaded = [];
var_dump(get_parent_class('AutoloadChild'));
var_dump($autoloaded);

$autoloaded = [];
var_dump(is_a('AutoloadImplementation', 'AutoloadInterface', true));
var_dump($autoloaded);

$autoloaded = [];
var_dump(is_a('AutoloadMissing', 'AutoloadInterface', true));
var_dump($autoloaded);

$autoloaded = [];
var_dump(is_a('AutoloadChild', 'AutoloadMissingInterface', true));
var_dump($autoloaded);

$autoloaded = [];
var_dump(is_subclass_of('AutoloadChild', 'AutoloadParent', false));
var_dump($autoloaded);

$instance = new AutoloadChild();
var_dump(is_a($instance, 'AutoloadInterface'));
var_dump(is_subclass_of($instance, 'AutoloadParent'));
var_dump(method_exists($instance, 'method'));
var_dump(property_exists($instance, 'value'));
var_dump(get_parent_class($instance));
