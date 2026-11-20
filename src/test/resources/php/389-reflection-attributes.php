<?php
#[Attribute(Attribute::TARGET_CLASS | Attribute::TARGET_METHOD | Attribute::TARGET_PROPERTY | Attribute::TARGET_CLASS_CONSTANT | Attribute::TARGET_PARAMETER | Attribute::TARGET_FUNCTION | Attribute::IS_REPEATABLE)]
class Marker {
    public function __construct(public string $name = 'marker', public array $options = []) {}
}

#[Attribute(Attribute::TARGET_CLASS)]
class Single {
    public function __construct(public int $value = 0) {}
}

class NotAnAttribute {
    public function __construct(public int $value = 0) {}
}

#[Marker('class', options: ['a' => 1])]
#[Single(5)]
#[NotAnAttribute]
class Attributed {
    #[Marker('property')]
    public $value;

    #[Marker('constant')]
    public const CONSTANT = 7;

    #[Marker('first')]
    #[Marker('second')]
    public function method(#[Marker('parameter')] int $parameter): void {}

    public function plain(): void {}
}

#[Marker('function')]
function attributedFunction(#[Marker('argument')] $argument): void {}

$r = new ReflectionClass('Attributed');
echo 'class attributes=';
foreach ($r->getAttributes() as $attribute) {
    echo $attribute->getName(), '@', $attribute->getTarget(), ',';
}
echo "\n";
$marker = $r->getAttributes('Marker')[0];
echo 'marker=', $marker->getName(), ' repeated=', (int)$marker->isRepeated(), ' target=', $marker->getTarget(),
    ' arguments=', json_encode($marker->getArguments()), "\n";
$instance = $marker->newInstance();
echo 'marker instance=', get_class($instance), '|', $instance->name, '|', json_encode($instance->options), "\n";
$single = $r->getAttributes('Single')[0];
echo 'single arguments=', json_encode($single->getArguments()), ' instance=', $single->newInstance()->value, "\n";
$filtered = $r->getAttributes('Marker', ReflectionAttribute::IS_INSTANCEOF);
echo 'filter count=', count($filtered), ' exact count=', count($r->getAttributes('Marker')), "\n";
echo 'instanceof object count=', count($r->getAttributes('Single', ReflectionAttribute::IS_INSTANCEOF)), "\n";

$method = $r->getMethod('method');
$methodAttributes = $method->getAttributes();
echo 'method attributes=', count($methodAttributes), ' target=', $methodAttributes[0]->getTarget(),
    ' repeated=', (int)$methodAttributes[0]->isRepeated(), ' args=', json_encode($methodAttributes[0]->getArguments()), "\n";
$parameter = $method->getParameters()[0];
$parameterAttribute = $parameter->getAttributes()[0];
echo 'parameter attribute=', $parameterAttribute->getName(), ' target=', $parameterAttribute->getTarget(),
    ' args=', json_encode($parameterAttribute->getArguments()), "\n";
$propertyAttribute = $r->getProperty('value')->getAttributes()[0];
echo 'property attribute=', $propertyAttribute->getName(), ' target=', $propertyAttribute->getTarget(),
    ' args=', json_encode($propertyAttribute->getArguments()), "\n";
$constantAttribute = $r->getReflectionConstant('CONSTANT')->getAttributes()[0];
echo 'constant attribute=', $constantAttribute->getName(), ' target=', $constantAttribute->getTarget(),
    ' args=', json_encode($constantAttribute->getArguments()), "\n";
$functionAttribute = (new ReflectionFunction('attributedFunction'))->getAttributes()[0];
echo 'function attribute=', $functionAttribute->getName(), ' target=', $functionAttribute->getTarget(),
    ' args=', json_encode($functionAttribute->getArguments()), "\n";

$repeatedAttributes = $method->getAttributes('Marker');
echo 'repeated=', (int)$repeatedAttributes[0]->isRepeated(), '|', (int)$repeatedAttributes[1]->isRepeated(), "\n";
echo 'repeated instance=', $repeatedAttributes[0]->newInstance()->name, "\n";

try {
    $r->getAttributes('NotAnAttribute')[0]->newInstance();
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
echo 'attribute constants=', Attribute::TARGET_CLASS, ',', Attribute::TARGET_METHOD, ',', Attribute::TARGET_PROPERTY,
    ',', Attribute::TARGET_CLASS_CONSTANT, ',', Attribute::TARGET_PARAMETER, ',', Attribute::TARGET_FUNCTION,
    ',', Attribute::TARGET_ALL, ',', Attribute::IS_REPEATABLE, ',', ReflectionAttribute::IS_INSTANCEOF, "\n";

#[Attribute(Attribute::TARGET_METHOD)]
class MethodOnly { public function __construct() {} }
#[MethodOnly]
class WrongTarget {}
try {
    (new ReflectionClass('WrongTarget'))->getAttributes()[0]->newInstance();
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
