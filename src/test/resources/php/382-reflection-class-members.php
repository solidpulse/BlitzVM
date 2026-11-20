<?php
interface Contract { public function run(); const CONTRACT = 'contract'; }
class Base {
    private $basePrivate = 'bp';
    protected $baseProtected = 'bprot';
    public $basePublic = 'bpub';
    public static $baseStatic = 'bs';
    public const BASE_CONST = 'base';
    private const BASE_PRIVATE = 'private';
    public function baseMethod() {}
    private function basePrivateMethod() {}
    protected static function baseStaticMethod() {}
    public function __construct() {}
}
class Member extends Base implements Contract {
    private $childPrivate = 'cp';
    protected $childProtected = 'cprot';
    public $childPublic = 'cpub';
    public static $childStatic = 'cs';
    public const CHILD_CONST = 'child';
    public function run() {}
    public function childMethod() {}
    private function childPrivateMethod() {}
    protected static function childStaticMethod() {}
}

$r = new ReflectionClass('Member');

echo 'constructor=', $r->getConstructor()->getName(), ' declaration=', $r->getConstructor()->getDeclaringClass()->getName(), "\n";
echo 'has child method=', (int)$r->hasMethod('CHILDMETHOD'), ' inherited=', (int)$r->hasMethod('baseMethod'), ' inherited private=', (int)$r->hasMethod('basePrivateMethod'), ' missing=', (int)$r->hasMethod('missing'), "\n";
echo 'get method case=', $r->getMethod('CHILDMETHOD')->getName(), "\n";
echo 'methods=';
foreach ($r->getMethods() as $method) {
    echo $method->getName(), '@', $method->getDeclaringClass()->getName(), ',';
}
echo "\n";
echo 'public methods=';
foreach ($r->getMethods(ReflectionMethod::IS_PUBLIC) as $method) {
    echo $method->getName(), ',';
}
echo "\n";
echo 'static methods=';
foreach ($r->getMethods(ReflectionMethod::IS_STATIC) as $method) {
    echo $method->getName(), ',';
}
echo "\n";
echo 'zero filter methods=', count($r->getMethods(0)), "\n";

echo 'properties=';
foreach ($r->getProperties() as $property) {
    echo $property->getName(), '@', $property->getDeclaringClass()->getName(), ',';
}
echo "\n";
echo 'public properties=';
foreach ($r->getProperties(ReflectionProperty::IS_PUBLIC) as $property) {
    echo $property->getName(), ',';
}
echo "\n";
echo 'static properties=';
foreach ($r->getProperties(ReflectionProperty::IS_STATIC) as $property) {
    echo $property->getName(), ',';
}
echo "\n";
echo 'has child property=', (int)$r->hasProperty('childPrivate'), ' inherited=', (int)$r->hasProperty('baseProtected'), ' inherited private=', (int)$r->hasProperty('basePrivate'), "\n";

echo 'constants=', json_encode($r->getConstants()), "\n";
echo 'constant names=';
foreach ($r->getReflectionConstants() as $constant) {
    echo $constant->getName(), '@', $constant->getDeclaringClass()->getName(), ',';
}
echo "\n";
echo 'has constant=', (int)$r->hasConstant('CHILD_CONST'), ' inherited private=', (int)$r->hasConstant('BASE_PRIVATE'), "\n";
echo 'get constant=', $r->getConstant('CHILD_CONST'), ' missing=', var_export($r->getConstant('missing'), true), "\n";
echo 'reflection constant=', $r->getReflectionConstant('CHILD_CONST')->getName(), ' missing=', var_export($r->getReflectionConstant('missing'), true), "\n";

echo 'static values=', json_encode($r->getStaticProperties()), "\n";
echo 'default values=', json_encode($r->getDefaultProperties()), "\n";
echo 'static read=', $r->getStaticPropertyValue('childStatic'), ' default=', $r->getStaticPropertyValue('missing', 'fallback'), "\n";
$r->setStaticPropertyValue('childStatic', 'changed');
echo 'static after set=', Member::$childStatic, "\n";

try {
    $r->getStaticPropertyValue('basePrivate');
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
try {
    $r->setStaticPropertyValue('missing', 1);
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
try {
    $r->getProperty('basePrivate');
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
try {
    $r->getMethod('missing');
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
