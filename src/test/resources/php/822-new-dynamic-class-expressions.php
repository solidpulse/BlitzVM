<?php
// new with a class name taken from a static property, an array element or a property:
// the parentheses after the class expression are the constructor's arguments.
class Builder {
    public function __construct(public string $tag = 'none') {}
}
class Factory {
    public static string $builder = Builder::class;
    public static array $map = ['b' => Builder::class];
    public string $cls = Builder::class;
    public static function make(string $tag): Builder { return new static::$builder($tag); }
    public static function viaSelf(): Builder { return new self::$builder('self'); }
    public static function viaMap(): Builder { return new static::$map['b']('map'); }
}
class Child extends Factory {
    public static string $builder = ChildBuilder::class;
}
class ChildBuilder extends Builder {}
var_dump(Factory::make('x')->tag, get_class(Child::make('y')), Factory::viaSelf()->tag, Factory::viaMap()->tag);
var_dump(get_class(new Factory::$builder), (new Factory::$builder('bare'))->tag);
$f = new Factory();
var_dump((new $f->cls('prop'))->tag);
$arr = ['k' => Builder::class];
var_dump((new $arr['k']('elem'))->tag);
$name = 'Builder';
var_dump((new $name('var'))->tag);
