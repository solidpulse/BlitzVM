<?php
// ReflectionAttribute: filters, arguments, repeats and newInstance().

#[Attribute(Attribute::TARGET_ALL)]
class BaseTag
{
    public function __construct(public string $label = 'base')
    {
    }
}

#[Attribute(Attribute::TARGET_CLASS | Attribute::TARGET_METHOD | Attribute::TARGET_PROPERTY
    | Attribute::TARGET_PARAMETER | Attribute::TARGET_CLASS_CONSTANT | Attribute::IS_REPEATABLE)]
class Tag extends BaseTag
{
}

#[Attribute(Attribute::TARGET_CLASS)]
class SingleClassTag
{
}

#[Tag('class-a')]
#[Tag('class-b')]
#[SingleClassTag]
class Marked
{
    #[Tag('constant')]
    public const VALUE = 1;

    #[Tag('property')]
    public string $name = 'n';

    #[Tag('method')]
    #[Tag(label: 'method-named')]
    public function run(#[Tag('parameter')] int $value): void
    {
    }
}

function dumpAttributes(array $attributes): void
{
    echo 'count=', count($attributes), "\n";
    foreach ($attributes as $attribute) {
        echo $attribute->getName(), '|', $attribute->getTarget(), '|', (int) $attribute->isRepeated(), '|';
        $arguments = $attribute->getArguments();
        echo json_encode($arguments, JSON_THROW_ON_ERROR), "\n";
    }
}

$class = new ReflectionClass(Marked::class);
dumpAttributes($class->getAttributes());
dumpAttributes($class->getAttributes(Tag::class));
dumpAttributes($class->getAttributes(BaseTag::class, ReflectionAttribute::IS_INSTANCEOF));
dumpAttributes($class->getAttributes(SingleClassTag::class));

$constant = $class->getReflectionConstant('VALUE');
dumpAttributes($constant->getAttributes());
$property = $class->getProperty('name');
dumpAttributes($property->getAttributes());
$method = $class->getMethod('run');
dumpAttributes($method->getAttributes());
$parameter = $method->getParameters()[0];
dumpAttributes($parameter->getAttributes());

$instances = $class->getAttributes(Tag::class);
foreach ($instances as $attribute) {
    $instance = $attribute->newInstance();
    echo get_class($instance), '|', $instance->label, "\n";
}
$single = $class->getAttributes(SingleClassTag::class)[0]->newInstance();
echo get_class($single), "\n";
$methodInstance = $method->getAttributes(Tag::class)[1]->newInstance();
echo $methodInstance->label, "\n";
$parameterInstance = $parameter->getAttributes(Tag::class)[0]->newInstance();
echo $parameterInstance->label, "\n";

$missing = $class->getAttributes('NoSuchAttribute');
var_dump(count($missing));

class NotAnAttribute
{
}

#[NotAnAttribute]
class BadlyMarked
{
}

try {
    (new ReflectionClass(BadlyMarked::class))->getAttributes()[0]->newInstance();
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}

$plain = new ReflectionClass(NotAnAttribute::class);
var_dump(count($plain->getAttributes()));
var_dump((new ReflectionMethod(Marked::class, 'run'))->getAttributes()[0]->getTarget());
