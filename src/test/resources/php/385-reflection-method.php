<?php
abstract class Shape {
    public const KIND = 'shape';
    abstract protected function area(): float;
    public function describe(string $prefix = 'shape'): string {
        return $prefix . ':' . $this->area();
    }
}
class Square extends Shape {
    public function __construct(private float $side = 1.0) {}
    protected function area(): float {
        return $this->side * $this->side;
    }
    public static function make(float $side): static {
        return new static($side);
    }
    private function secret(): string {
        return 'secret';
    }
}

$describe = new ReflectionMethod('Square', 'describe');
echo 'name=', $describe->getName(), ' short=', $describe->getShortName(), ' declaring=', $describe->getDeclaringClass()->getName(), "\n";
echo 'public=', (int)$describe->isPublic(), ' protected=', (int)$describe->isProtected(), ' private=', (int)$describe->isPrivate(),
    ' static=', (int)$describe->isStatic(), ' abstract=', (int)$describe->isAbstract(), ' final=', (int)$describe->isFinal(),
    ' constructor=', (int)$describe->isConstructor(), ' modifiers=', $describe->getModifiers(), "\n";
echo 'returnsReference=', (int)$describe->returnsReference(), ' variadic=', (int)$describe->isVariadic(),
    ' returnType=', (string)$describe->getReturnType(), ' hasReturnType=', (int)$describe->hasReturnType(), "\n";
echo 'invoke=', $describe->invoke(new Square(3), 'square'), "\n";
echo 'invokeArgs=', $describe->invokeArgs(new Square(4), ['prefix' => 'named']), "\n";
echo 'invoke missing default=', $describe->invoke(new Square(2)), "\n";

$static = new ReflectionMethod('Square', 'make');
echo 'static invoke=', $static->invoke(null, 5)->describe('made'), "\n";
$fromObject = new ReflectionMethod(new Square(2), 'describe');
echo 'from object=', $fromObject->invoke(new Square(2), 'obj'), "\n";
$fromString = new ReflectionMethod('Square::describe');
echo 'from string=', $fromString->invoke(new Square(2), 'str'), "\n";
$secret = new ReflectionMethod('Square', 'secret');
echo 'private invoke=', $secret->invoke(new Square()), "\n";
$closure = $describe->getClosure(new Square(6));
echo 'closure=', $closure('closed'), "\n";
$staticClosure = $static->getClosure();
echo 'static closure=', $staticClosure(3)->describe('made'), "\n";

$abstract = new ReflectionMethod('Shape', 'area');
echo 'abstract name=', $abstract->getName(), ' declaring=', $abstract->getDeclaringClass()->getName(),
    ' abstract=', (int)$abstract->isAbstract(), ' modifiers=', $abstract->getModifiers(),
    ' file=', basename($abstract->getFileName()), ' start=', $abstract->getStartLine(), ' end=', $abstract->getEndLine(), "\n";

try {
    $describe->invoke(null);
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
try {
    $describe->invoke(new stdClass());
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
try {
    $describe->invoke(4);
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
try {
    $describe->getClosure();
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
try {
    new ReflectionMethod('Square', 'missing');
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
try {
    new ReflectionMethod('MissingClass', 'method');
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
try {
    new ReflectionMethod('Square');
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
