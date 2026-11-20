<?php
class ErrorBase { private $privateProperty; private function privateMethod() {} }
class ErrorChild extends ErrorBase { public $publicProperty; public function method($value = 1) {} }
abstract class ErrorAbstract {}
interface ErrorInterface {}
class ErrorNoConstructor {}
class ErrorPrivateConstructor { private function __construct() {} }

function capture(string $label, callable $callable): void {
    try {
        $callable();
        echo $label, "=no error\n";
    } catch (Throwable $e) {
        echo $label, '=', get_class($e), ': ', $e->getMessage(), "\n";
    }
}

capture('unknown class', fn() => new ReflectionClass('MissingClass'));
capture('unknown method', fn() => (new ReflectionClass('ErrorChild'))->getMethod('missingMethod'));
capture('inherited private method', fn() => (new ReflectionClass('ErrorChild'))->getMethod('privateMethod')->getName());
capture('unknown property', fn() => (new ReflectionClass('ErrorChild'))->getProperty('missingProperty'));
capture('inherited private property', fn() => (new ReflectionClass('ErrorChild'))->getProperty('privateProperty'));
capture('unknown function', fn() => new ReflectionFunction('missingFunction'));
capture('unknown method class', fn() => new ReflectionMethod('MissingClass', 'method'));
capture('unknown method name', fn() => new ReflectionMethod('ErrorChild', 'missingMethod'));
capture('bad method form', fn() => new ReflectionMethod('ErrorChild'));
capture('unknown property class', fn() => new ReflectionProperty('MissingClass', 'property'));
capture('unknown property name', fn() => new ReflectionProperty('ErrorChild', 'missingProperty'));
capture('unknown constant class', fn() => new ReflectionClassConstant('MissingClass', 'VALUE'));
capture('unknown constant name', fn() => new ReflectionClassConstant('ErrorChild', 'VALUE'));

$method = new ReflectionMethod('ErrorChild', 'method');
$required = (new ReflectionFunction(function ($required) {}))->getParameters()[0];
capture('default value', fn() => $required->getDefaultValue());
capture('default constant', fn() => $required->isDefaultValueConstant());
capture('default constant name', fn() => $required->getDefaultValueConstantName());

capture('invoke without object', fn() => $method->invoke(null));
capture('invoke wrong object', fn() => $method->invoke(new stdClass()));
capture('invoke non object', fn() => $method->invoke(5));
capture('closure without object', fn() => $method->getClosure());
capture('newInstance interface', fn() => (new ReflectionClass('ErrorInterface'))->newInstance());
capture('newInstance abstract', fn() => (new ReflectionClass('ErrorAbstract'))->newInstance());
capture('newInstance private constructor', fn() => (new ReflectionClass('ErrorPrivateConstructor'))->newInstance());
capture('newInstance args without constructor', fn() => (new ReflectionClass('ErrorNoConstructor'))->newInstanceArgs([1]));
capture('newInstance unknown named', fn() => (new ReflectionClass('ErrorChild'))->newInstanceArgs(['missing' => 1]));
capture('reflection object string', fn() => new ReflectionObject('string'));
capture('implements non interface', fn() => (new ReflectionClass('ErrorChild'))->implementsInterface('stdClass'));
capture('implements missing', fn() => (new ReflectionClass('ErrorChild'))->implementsInterface('MissingInterface'));
capture('subclass missing', fn() => (new ReflectionClass('ErrorChild'))->isSubclassOf('MissingClass'));
capture('static property missing', fn() => (new ReflectionClass('ErrorChild'))->getStaticPropertyValue('missing'));
capture('static property set missing', fn() => (new ReflectionClass('ErrorChild'))->setStaticPropertyValue('missing', 1));
capture('property value without object', fn() => (new ReflectionProperty('ErrorChild', 'publicProperty'))->getValue());
