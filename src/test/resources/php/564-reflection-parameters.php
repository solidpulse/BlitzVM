<?php
// ReflectionMethod/Function::getParameters() and getNumberOf*.

class ParameterTarget
{
    public const LIMIT = 10;

    public function __construct(
        public string $name,
        protected ?int $age = null,
        int|string $code = self::LIMIT,
    ) {
    }

    public function run(
        int $first,
        ?string $second = 'two',
        int|string $third = ParameterTarget::LIMIT,
        array &$fourth = [],
        string ...$rest,
    ): int|string {
        return $first;
    }

    public static function create(self $other, ?callable $factory = null): static
    {
        return $other;
    }
}

function plainFunction(int $a, string $b = 'b', &$c = null, float ...$numbers): ?string
{
    return $b;
}

function describe(ReflectionParameter $parameter): void
{
    $type = $parameter->getType();
    $hasDefault = $parameter->isDefaultValueAvailable();
    $constant = $hasDefault && $parameter->isDefaultValueConstant();
    echo $parameter->getName(), '|', $parameter->getPosition(), '|',
        (int) $parameter->isOptional(), (int) $parameter->isVariadic(), '|',
        (int) $parameter->isPassedByReference(), (int) $parameter->canBePassedByValue(), '|',
        (int) $parameter->isPromoted(), '|', (int) $parameter->hasType(), '|',
        $type === null ? 'none' : (string) $type, '|',
        (int) $parameter->allowsNull(), '|',
        (int) $hasDefault, '|',
        $hasDefault ? var_export($parameter->getDefaultValue(), true) : '-', '|',
        (int) $constant, '|',
        $constant ? $parameter->getDefaultValueConstantName() : '-', '|',
        $parameter->getDeclaringFunction()->isClosure() ? '{closure}'
            : $parameter->getDeclaringFunction()->getName(), '|',
        $parameter->getDeclaringClass()?->getName() ?? '-', '|',
        $parameter->getClass()?->getName() ?? '-', "\n";
}

$constructor = (new ReflectionClass(ParameterTarget::class))->getConstructor();
echo 'ctor params=', $constructor->getNumberOfParameters(), ' required=', $constructor->getNumberOfRequiredParameters(), "\n";
foreach ($constructor->getParameters() as $parameter) {
    describe($parameter);
}

$run = new ReflectionMethod(ParameterTarget::class, 'run');
echo 'run params=', $run->getNumberOfParameters(), ' required=', $run->getNumberOfRequiredParameters(), "\n";
foreach ($run->getParameters() as $parameter) {
    describe($parameter);
}

$create = new ReflectionMethod(ParameterTarget::class, 'create');
echo 'create params=', $create->getNumberOfParameters(), ' required=', $create->getNumberOfRequiredParameters(), "\n";
foreach ($create->getParameters() as $parameter) {
    describe($parameter);
}
$returnType = $create->getReturnType();
echo 'return=', $returnType === null ? 'none' : (string) $returnType, '|', (int) $create->hasReturnType(), "\n";

$function = new ReflectionFunction('plainFunction');
echo 'function params=', $function->getNumberOfParameters(), ' required=', $function->getNumberOfRequiredParameters(), "\n";
foreach ($function->getParameters() as $parameter) {
    describe($parameter);
}

$closure = new ReflectionFunction(function (int $x, ?ParameterTarget $target = null): void {
});
echo 'closure params=', $closure->getNumberOfParameters(), ' required=', $closure->getNumberOfRequiredParameters(), "\n";
foreach ($closure->getParameters() as $parameter) {
    describe($parameter);
}

$byName = $run->getParameters()[1];
echo $byName->getName(), '|', $byName->getPosition(), '|', $byName->getDeclaringClass()?->getName() ?? '-', "\n";

$promoted = $run->getParameters()[0];
var_dump($promoted->getType() instanceof ReflectionNamedType, (string) $promoted->getType());
echo (new ReflectionFunction('strlen'))->getNumberOfParameters(), '|',
    (new ReflectionFunction('strlen'))->getNumberOfRequiredParameters(), "\n";
