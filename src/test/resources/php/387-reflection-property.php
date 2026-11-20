<?php
#[Marker('class')]
#[AllowDynamicProperties]
class PropertyHolder {
    #[Marker('public')]
    public $public = 'public-default';
    protected $protected = 'protected-default';
    private $private = 'private-default';
    public static $static = 'static-default';
    protected static $protectedStatic = 'protected-static-default';
    public ?string $typed = null;
    public readonly int $readonly;
    public function __construct() {
        $this->readonly = 10;
    }
}

$r = new ReflectionClass('PropertyHolder');
foreach ($r->getProperties() as $property) {
    echo $property->getName(),
        ' declaring=', $property->getDeclaringClass()->getName(),
        ' public=', (int)$property->isPublic(),
        ' protected=', (int)$property->isProtected(),
        ' private=', (int)$property->isPrivate(),
        ' static=', (int)$property->isStatic(),
        ' readonly=', (int)$property->isReadOnly(),
        ' promoted=', (int)$property->isPromoted(),
        ' default=', (int)$property->isDefault(),
        ' hasDefault=', (int)$property->hasDefaultValue(),
        ' modifiers=', $property->getModifiers(),
        ' type=', $property->hasType() ? (string)$property->getType() : 'none',
        ' attributes=';
    foreach ($property->getAttributes() as $attribute) {
        echo $attribute->getName(), '@', $attribute->getTarget(), ' ';
    }
    echo "\n";
}

$object = new PropertyHolder();
$public = new ReflectionProperty('PropertyHolder', 'public');
$protected = new ReflectionProperty('PropertyHolder', 'protected');
$private = new ReflectionProperty('PropertyHolder', 'private');
$static = new ReflectionProperty('PropertyHolder', 'static');
$readonly = new ReflectionProperty('PropertyHolder', 'readonly');

echo 'values=', $public->getValue($object), '|', $protected->getValue($object), '|', $private->getValue($object), '|', $static->getValue(), "\n";
$private->setValue($object, 'private-changed');
$protected->setValue($object, 'protected-changed');
$public->setValue($object, 'public-changed');
$static->setValue('static-changed');
echo 'after=', $public->getValue($object), '|', $protected->getValue($object), '|', $private->getValue($object), '|', $static->getValue(), "\n";
echo 'static default value=', var_export($static->getDefaultValue(), true), ' instance default=', var_export($public->getDefaultValue(), true), "\n";
echo 'readonly initialized=', (int)$readonly->isInitialized($object), ' value=', $readonly->getValue($object), "\n";
$fresh = (new ReflectionClass('PropertyHolder'))->newInstanceWithoutConstructor();
echo 'readonly fresh=', (int)$readonly->isInitialized($fresh), "\n";

$dynamicObject = new PropertyHolder();
$dynamicObject->dynamic = 'dynamic-value';
$dynamic = new ReflectionProperty($dynamicObject, 'dynamic');
echo 'dynamic name=', $dynamic->getName(), ' default=', (int)$dynamic->isDefault(), ' value=', $dynamic->getValue($dynamicObject), "\n";
$dynamic->setValue($dynamicObject, 'dynamic-changed');
echo 'dynamic after=', $dynamicObject->dynamic, "\n";

try {
    $public->getValue();
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
try {
    new ReflectionProperty('PropertyHolder', 'missing');
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}

class TypedStatic { public static int $value; }
$typedStatic = new ReflectionClass('TypedStatic');
echo 'typed static properties=', json_encode($typedStatic->getStaticProperties()),
    ' initialized=', (int)(new ReflectionProperty('TypedStatic', 'value'))->isInitialized(), "\n";
$typedStatic->setStaticPropertyValue('value', 4);
echo 'typed static after=', json_encode($typedStatic->getStaticProperties()),
    ' initialized=', (int)(new ReflectionProperty('TypedStatic', 'value'))->isInitialized(), "\n";
