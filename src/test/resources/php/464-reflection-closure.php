<?php
set_error_handler(function ($n, $s) { echo "warning:$s\n"; return true; });

class ClosureParent {
    public int $value = 2;

    public function make(int $factor): Closure {
        $local = $factor;
        return function (int $x) use ($local) {
            return $local + $x + $this->value;
        };
    }

    public function makeStatic(int $factor): Closure {
        $local = $factor;
        return static function (int $x) use ($local) {
            return $local * $x;
        };
    }

    public function makeArrow(): Closure {
        return fn (int $x) => $x + $this->value;
    }

    public function makeNoUse(): Closure {
        return function () {
            return 1;
        };
    }

    public static function staticMethod(int $x): int {
        return $x;
    }

    public function method(int $x): int {
        return $x;
    }
}

class ClosureChild extends ClosureParent {}

function plainFunction(): void {}

function report(string $label, ReflectionFunctionAbstract $function): void {
    echo $label;
    echo ' closure=', (int) $function->isClosure();
    if ($function instanceof ReflectionFunction) {
        echo ' anonymous=', (int) $function->isAnonymous();
    }
    echo ' static=', (int) $function->isStatic();
    echo ' this=', $function->getClosureThis() === null ? 'null' : get_class($function->getClosureThis());
    echo ' called=', $function->getClosureCalledClass()?->getName() ?? 'null';
    echo ' scope=', $function->getClosureScopeClass()?->getName() ?? 'null';
    echo ' used=';
    var_export($function->getClosureUsedVariables());
    echo "\n";
}

$parent = new ClosureParent();
$child = new ClosureChild();

$anonymous = $child->make(3);
$staticAnonymous = $child->makeStatic(4);
$arrow = $child->makeArrow();
$noUse = $child->makeNoUse();
$firstClassMethod = $child->method(...);
$firstClassStatic = ClosureParent::staticMethod(...);
$namedFunction = strlen(...);
$fromCallable = Closure::fromCallable([$child, 'method']);
$bound = (function () { return 1; })->bindTo($child, ClosureParent::class);

$counter = 1;
$byRef = function () use (&$counter) {
    return $counter;
};
$globalUse = function () use ($parent) {
    return $parent;
};

report('anonymous', new ReflectionFunction($anonymous));
report('static anonymous', new ReflectionFunction($staticAnonymous));
report('arrow', new ReflectionFunction($arrow));
report('no use', new ReflectionFunction($noUse));
report('first-class method', new ReflectionFunction($firstClassMethod));
report('first-class static', new ReflectionFunction($firstClassStatic));
report('named function closure', new ReflectionFunction($namedFunction));
report('fromCallable', new ReflectionFunction($fromCallable));
report('bound', new ReflectionFunction($bound));
report('by-ref', new ReflectionFunction($byRef));
report('global use', new ReflectionFunction($globalUse));

echo 'anonymous result=', $anonymous(1), "\n";
echo 'static anonymous result=', $staticAnonymous(2), "\n";
echo 'arrow result=', $arrow(3), "\n";
echo 'first-class method result=', $firstClassMethod(6), "\n";
echo 'first-class static result=', $firstClassStatic(7), "\n";
echo 'named function result=', $namedFunction('four'), "\n";
echo 'fromCallable result=', $fromCallable(8), "\n";

$byRefVars = (new ReflectionFunction($byRef))->getClosureUsedVariables();
var_export($byRefVars);
echo "\n";
$byRefVars['counter'] = 10;
echo 'counter after array write=', $counter, "\n";
$counter = 20;
echo 'counter after variable write=', $byRef(), "\n";
var_export((new ReflectionFunction($byRef))->getClosureUsedVariables());
echo "\n";

$globalVars = (new ReflectionFunction($globalUse))->getClosureUsedVariables();
var_dump($globalVars['parent'] === $parent);

report('plain function', new ReflectionFunction('plainFunction'));
report('internal function', new ReflectionFunction('strlen'));

$method = new ReflectionMethod(ClosureParent::class, 'method');
$staticMethod = new ReflectionMethod(ClosureParent::class, 'staticMethod');
echo 'method static=', (int) $method->isStatic(), ' called=', $method->getClosureCalledClass()?->getName() ?? 'null', "\n";
echo 'static method static=', (int) $staticMethod->isStatic(), ' called=', $staticMethod->getClosureCalledClass()?->getName() ?? 'null', "\n";
var_export($method->getClosureUsedVariables());
echo "\n";
var_export($staticMethod->getClosureUsedVariables());
echo "\n";

$methodClosure = $method->getClosure($child);
report('method getClosure', new ReflectionFunction($methodClosure));
