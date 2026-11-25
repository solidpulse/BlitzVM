<?php
class BaseWithConstructor
{
    public function __construct(public int $value, public string $label = 'base')
    {
    }
}

class InheritsConstructor extends BaseWithConstructor
{
}

trait TraitConstructor
{
    public function __construct(public string $text)
    {
    }
}

class UsesTraitConstructor
{
    use TraitConstructor;
}

class NoConstructor
{
    public int $value = 7;
}

echo (new BaseWithConstructor(1))->value, "\n";
echo (new InheritsConstructor(2))->value, ' ', (new InheritsConstructor(3, 'child'))->label, "\n";
echo (new UsesTraitConstructor('trait'))->text, "\n";
echo (new NoConstructor())->value, "\n";

// One dynamic new site that instantiates several classes.
function instantiate(string $class, int $value): BaseWithConstructor
{
    return new $class($value, 'dynamic');
}

foreach ([BaseWithConstructor::class, InheritsConstructor::class] as $index => $class) {
    $object = instantiate($class, $index + 10);
    echo get_class($object), ':', $object->value, ':', $object->label, "\n";
}

// An anonymous class has its own template and constructor.
$anonymous = new class(5) {
    public function __construct(public int $value)
    {
    }
};
echo $anonymous instanceof NoConstructor ? 'no' : 'anonymous', ':', $anonymous->value, "\n";

// A class declared after the first call through the constructor site.
function constructLate(): NoConstructor
{
    return new LateConstructed();
}
try {
    echo constructLate()->value, "\n";
} catch (Error $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
if (true) {
    final class LateConstructed extends NoConstructor
    {
    }
}
echo constructLate()->value, "\n";

// Constructor arguments by reference reach the property through the parameter.
function setThroughConstructor(int &$value): BaseWithConstructor
{
    return new BaseWithConstructor($value);
}
$number = 4;
$object = setThroughConstructor($number);
$number = 9;
echo $object->value, ' ', $number, "\n";
