<?php
// new with a parenthesized class expression: new ($obj->factory())(...), new ('Class')(...).
class Built {
    public function __construct(public string $tag = 'none') {}
}
class Factory {
    public static string $class = Built::class;
    public function className(): string { return Built::class; }
}
$f = new Factory();
var_dump((new ($f->className())('method'))->tag);
var_dump((new ('Built')('literal'))->tag);
var_dump((new (Factory::$class)('static-prop'))->tag);
var_dump((new (Built::class)('class-const'))->tag);
$name = 'Built';
var_dump((new ($name)('variable'))->tag);
try {
    new ('NoSuchClass')();
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
