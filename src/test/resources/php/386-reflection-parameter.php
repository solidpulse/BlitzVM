<?php
const GLOBAL_LIMIT = 42;
class Dependency { public $name = 'dependency'; }
class Target { const LIMIT = 7; }

#[Marker]
class ParameterHolder {
    const LOCAL = 9;

    public function __construct(
        #[Marker] public readonly int $id,
        protected ?string $label = null
    ) {}

    public function run(
        $required,
        $number = 1,
        $text = 'x',
        $nothing = null,
        $global = GLOBAL_LIMIT,
        $self = self::LOCAL,
        $target = Target::LIMIT,
        $new = new Dependency(),
        $array = [1, 2],
        $limit = PHP_INT_MAX,
        ?int $nullable = null,
        int|string $union = 'u',
        &$reference = 5,
        ...$rest
    ): ?string {
        return null;
    }

    public function typed(Dependency $dependency, ?Target $target, int|Target $union): void {}
}

function describe(ReflectionParameter $parameter): void {
    echo $parameter->getName(), ' position=', $parameter->getPosition(),
        ' type=', $parameter->hasType() ? (string)$parameter->getType() : 'none',
        ' allowsNull=', (int)$parameter->allowsNull(),
        ' optional=', (int)$parameter->isOptional(),
        ' defaultAvailable=', (int)$parameter->isDefaultValueAvailable();
    if ($parameter->isDefaultValueAvailable()) {
        $default = $parameter->getDefaultValue();
        echo ' default=', is_object($default) ? 'object:' . get_class($default) : var_export($default, true),
            ' constant=', (int)$parameter->isDefaultValueConstant(),
            ' constantName=', var_export($parameter->getDefaultValueConstantName(), true);
    }
    echo ' variadic=', (int)$parameter->isVariadic(),
        ' byReference=', (int)$parameter->isPassedByReference(),
        ' byValue=', (int)$parameter->canBePassedByValue(),
        ' promoted=', (int)$parameter->isPromoted(),
        ' declaring=', $parameter->getDeclaringFunction()->getName(),
        ' declaringClass=', $parameter->getDeclaringClass() === null ? 'null' : $parameter->getDeclaringClass()->getName(),
        "\n";
}

$constructor = new ReflectionMethod('ParameterHolder', '__construct');
foreach ($constructor->getParameters() as $parameter) {
    describe($parameter);
}
$run = new ReflectionMethod('ParameterHolder', 'run');
foreach ($run->getParameters() as $parameter) {
    describe($parameter);
}
$typed = new ReflectionMethod('ParameterHolder', 'typed');
foreach ($typed->getParameters() as $parameter) {
    describe($parameter);
    echo '  class=', $parameter->getClass() === null ? 'none' : $parameter->getClass()->getName(), "\n";
}
echo 'constructor parameters=', $constructor->getNumberOfParameters(), ' required=', $constructor->getNumberOfRequiredParameters(), "\n";
echo 'run parameters=', $run->getNumberOfParameters(), ' required=', $run->getNumberOfRequiredParameters(), "\n";
$promoted = (new ReflectionClass('ParameterHolder'))->getProperty('id');
echo 'property promoted=', (int)$promoted->isPromoted(), ' readonly=', (int)$promoted->isReadOnly(), ' type=', (string)$promoted->getType(), "\n";

$required = $run->getParameters()[0];
try {
    $required->getDefaultValue();
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
try {
    $required->isDefaultValueConstant();
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
try {
    $required->getDefaultValueConstantName();
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
