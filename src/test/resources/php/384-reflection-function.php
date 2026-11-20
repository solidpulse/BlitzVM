<?php
#[Route('/function')]
function handle(
    #[Param] ?int $count,
    string $name = 'default',
    int ...$rest
): string {
    static $calls = 1;
    static $last;
    $calls++;
    $last = $name;
    return $count . ':' . $name . ':' . implode(',', $rest);
}

function noTypes($x) {
    return $x;
}

class Handler {
    public function handleValue(int $value = 5): int {
        return $value * 2;
    }
}

function describe(ReflectionFunction $function): void {
    echo $function->getName(), ' short=', $function->getShortName(), ' closure=', (int)$function->isClosure(),
        ' internal=', (int)$function->isInternal(), ' user=', (int)$function->isUserDefined(),
        ' parameters=', $function->getNumberOfParameters(), ' required=', $function->getNumberOfRequiredParameters(),
        ' variadic=', (int)$function->isVariadic(), ' returnsReference=', (int)$function->returnsReference(),
        ' returnType=', $function->hasReturnType() ? (string)$function->getReturnType() : 'none', "\n";
}

$reflection = new ReflectionFunction('handle');
describe($reflection);
echo 'file=', basename($reflection->getFileName()), ' start=', $reflection->getStartLine(), ' end=', $reflection->getEndLine(), "\n";
echo 'attributes=';
foreach ($reflection->getAttributes() as $attribute) {
    echo $attribute->getName(), '@', $attribute->getTarget(), ' ';
}
echo "\n";
foreach ($reflection->getParameters() as $parameter) {
    echo $parameter->getName(), ': type=', $parameter->hasType() ? (string)$parameter->getType() : 'none',
        ' optional=', (int)$parameter->isOptional(),
        ' default=', $parameter->isDefaultValueAvailable() ? var_export($parameter->getDefaultValue(), true) : 'none',
        ' variadic=', (int)$parameter->isVariadic(),
        ' declaring=', $parameter->getDeclaringFunction()->getName(),
        ' declaringClass=', $parameter->getDeclaringClass() === null ? 'null' : $parameter->getDeclaringClass()->getName(),
        "\n";
}
echo 'invoke=', $reflection->invoke(2, 'a', 3, 4), "\n";
echo 'invokeArgs=', $reflection->invokeArgs([2, 'b']), "\n";
echo 'invokeNamed=', $reflection->invokeArgs(['name' => 'c', 'count' => 2]), "\n";
echo 'staticVariables=', json_encode($reflection->getStaticVariables()), "\n";
$noTypes = new ReflectionFunction('noTypes');
describe($noTypes);
echo 'noTypes parameter type=', var_export($noTypes->getParameters()[0]->getType(), true), ' allowsNull=', (int)$noTypes->getParameters()[0]->allowsNull(), "\n";

$closure = function (int $value): int {
    return $value + 1;
};
$closureReflection = new ReflectionFunction($closure);
echo 'closure name prefix=', strpos($closureReflection->getName(), '{closure:') === 0 ? 'yes' : 'no', "\n";
echo 'closure invoke=', $closureReflection->invoke(4), ' args=', $closureReflection->invokeArgs(['value' => 9]), "\n";
echo 'closure this=', var_export($closureReflection->getClosureThis(), true), ' scope=', var_export($closureReflection->getClosureScopeClass(), true), "\n";
$roundTrip = $closureReflection->getClosure();
echo 'closure roundtrip=', $roundTrip(10), "\n";

$firstClass = strlen(...);
$firstClassReflection = new ReflectionFunction($firstClass);
echo 'first class name=', $firstClassReflection->getName(), ' closure=', (int)$firstClassReflection->isClosure(),
    ' internal=', (int)$firstClassReflection->isInternal(), ' invoke=', $firstClassReflection->invoke('four'), "\n";

$methodClosure = (new Handler())->handleValue(...);
$methodReflection = new ReflectionFunction($methodClosure);
echo 'method closure name=', $methodReflection->getName(), ' this=', get_class($methodReflection->getClosureThis()),
    ' scope=', $methodReflection->getClosureScopeClass()->getName(), ' invoke=', $methodReflection->invoke(6), "\n";

try {
    new ReflectionFunction('missingFunction');
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
try {
    new ReflectionFunction([new Handler(), 'handleValue']);
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
