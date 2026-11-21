<?php
set_error_handler(function ($n, $s) { echo "warning:$s\n"; return true; });

/*
 * The parts of Symfony's polyfill-deepclone pattern this fixture needs, written out
 * here so the fixture does not depend on the vendor file: objects are listed by class
 * and index, properties are read with (array) and written back with ReflectionProperty
 * after newInstanceWithoutConstructor(), closures keep their ReflectionFunction metadata.
 */

enum DeepStatus: string {
    case Active = 'active';
    case Archived = 'archived';
}

final readonly class DeepMoney {
    public function __construct(
        public int $amount,
        public string $currency,
    ) {
    }
}

final class DeepAddress {
    public function __construct(
        public string $street,
        public string $city,
    ) {
    }
}

final class DeepProfile {
    private string $secret;
    protected string $note;

    public int $views = 0 {
        set {
            $this->views = $value * 2;
        }
    }

    public DeepMethod $method;

    public function __construct(
        public string $name,
        public DeepMoney $balance,
        public DeepAddress $address,
        public DeepStatus $status,
        public Closure $formatter,
        public Closure $length,
        string $secret,
        string $note,
    ) {
        $this->secret = $secret;
        $this->note = $note;
    }

    public function makeFormatter(int $factor): Closure {
        return function (int $value) use ($factor): int {
            return $value * $factor + strlen($this->name);
        };
    }

    public function secret(): string {
        return $this->secret;
    }

    public function note(): string {
        return $this->note;
    }
}

final class DeepMethod {
    public function __construct(
        private readonly ReflectionMethod $reflectionMethod,
    ) {
    }

    public function methodName(): string {
        return $this->reflectionMethod->getName();
    }

    public function __serialize(): array {
        return [
            'class' => $this->reflectionMethod->getDeclaringClass()->getName(),
            'method' => $this->reflectionMethod->getName(),
        ];
    }

    public function __unserialize(array $data): void {
        $this->reflectionMethod = new ReflectionMethod(
            objectOrMethod: $data['class'],
            method: $data['method'],
        );
    }
}

function deepclone_prepare(mixed $value, array &$state): mixed {
    if ($value instanceof Closure) {
        $reflection = new ReflectionFunction($value);
        $descriptor = [
            'name' => $reflection->getName(),
            'anonymous' => $reflection->isAnonymous(),
            'static' => $reflection->isStatic(),
            'called' => $reflection->getClosureCalledClass()?->getName(),
            'scope' => $reflection->getClosureScopeClass()?->getName(),
            'used' => deepclone_prepare($reflection->getClosureUsedVariables(), $state),
            'this' => $reflection->getClosureThis() === null
                ? null
                : deepclone_prepare($reflection->getClosureThis(), $state),
        ];
        if ($reflection->isAnonymous()) {
            $descriptor['file'] = $reflection->getFileName();
            $descriptor['start'] = $reflection->getStartLine();
            $descriptor['end'] = $reflection->getEndLine();
        }

        return ['__closure' => $descriptor];
    }

    if (is_object($value)) {
        if ($value instanceof UnitEnum) {
            return $value;
        }

        $id = spl_object_id($value);
        if (isset($state['seen'][$id])) {
            return ['__object' => $state['seen'][$id]];
        }

        $class = $value::class;
        $reflection = new ReflectionClass($class);
        $cloneable = $reflection->isCloneable() && !$reflection->hasMethod('__clone');
        $index = count($state['objects']);
        $state['seen'][$id] = $index;
        $state['objects'][] = null;

        if (method_exists($value, '__serialize')) {
            $properties = deepclone_prepare($value->__serialize(), $state);
            $serialized = true;
        } else {
            $properties = deepclone_prepare((array) $value, $state);
            $serialized = false;
        }

        $state['objects'][$index] = [
            'class' => $class,
            'cloneable' => $cloneable,
            'serialized' => $serialized,
            'properties' => $properties,
        ];

        return ['__object' => $index];
    }

    if (is_array($value)) {
        $out = [];
        foreach ($value as $key => $item) {
            $out[$key] = deepclone_prepare($item, $state);
        }
        return $out;
    }

    return $value;
}

function deepclone_to_array(mixed $value): array {
    $state = ['objects' => [], 'seen' => []];
    $root = deepclone_prepare($value, $state);

    return ['root' => $root, 'objects' => $state['objects']];
}

function deepclone_restore(mixed $value, array &$objects, array &$data): mixed {
    if (is_array($value)) {
        if (isset($value['__object'])) {
            return $objects[$value['__object']];
        }
        if (isset($value['__closure'])) {
            return deepclone_restore_closure($value['__closure'], $objects, $data);
        }
        $out = [];
        foreach ($value as $key => $item) {
            $out[$key] = deepclone_restore($item, $objects, $data);
        }
        return $out;
    }

    return $value;
}

function deepclone_restore_closure(array $descriptor, array &$objects, array &$data): Closure {
    $used = [];
    foreach ($descriptor['used'] as $name => $value) {
        $used[$name] = deepclone_restore($value, $objects, $data);
    }

    if (!$descriptor['anonymous']) {
        if ($descriptor['this'] !== null) {
            $object = deepclone_restore($descriptor['this'], $objects, $data);
            $method = new ReflectionMethod($descriptor['scope'] ?? $object::class, $descriptor['name']);
            return $method->getClosure($object);
        }
        if ($descriptor['scope'] !== null) {
            return Closure::fromCallable([$descriptor['scope'], $descriptor['name']]);
        }
        return Closure::fromCallable($descriptor['name']);
    }

    $lines = file($descriptor['file']);
    $start = $descriptor['start'] - 1;
    $end = $descriptor['end'] - 1;
    $offset = strpos($lines[$start], 'function');
    if ($offset === false) {
        $offset = strpos($lines[$start], 'fn ');
    }
    $source = substr($lines[$start], $offset);
    for ($i = $start + 1; $i <= $end; $i++) {
        $source .= $lines[$i];
    }
    $source = rtrim(trim($source), ';');

    $factory = function (string $source, array $used): Closure {
        extract($used);
        return eval('return ' . $source . ';');
    };
    $closure = $factory($source, $used);

    if (!$descriptor['static'] && $descriptor['scope'] !== null) {
        $object = $descriptor['this'] === null
            ? null
            : deepclone_restore($descriptor['this'], $objects, $data);
        $closure = $closure->bindTo($object, $descriptor['scope']);
    }

    return $closure;
}

function deepclone_from_array(array $data): mixed {
    $objects = [];

    foreach ($data['objects'] as $index => $entry) {
        $reflection = new ReflectionClass($entry['class']);
        if (!$reflection->isCloneable()) {
            throw new RuntimeException('Not cloneable: ' . $entry['class']);
        }
        $objects[$index] = $reflection->newInstanceWithoutConstructor();
    }

    foreach ($data['objects'] as $index => $entry) {
        $object = $objects[$index];
        $properties = deepclone_restore($entry['properties'], $objects, $data);

        if ($entry['serialized']) {
            $object->__unserialize($properties);
            continue;
        }

        foreach ($properties as $key => $value) {
            $name = (string) $key;
            $declaring = $entry['class'];
            if (str_starts_with($name, "\0")) {
                $end = strpos($name, "\0", 1);
                $scope = substr($name, 1, $end - 1);
                $name = substr($name, $end + 1);
                if ($scope !== '*') {
                    $declaring = $scope;
                }
            }

            $property = new ReflectionProperty($declaring, $name);
            if ($property->isStatic() || $property->isVirtual()) {
                continue;
            }
            if (count($property->getHooks()) > 0 && !$property->isVirtual()) {
                $property->setRawValue($object, $value);
            } elseif ($property->isReadOnly()) {
                if (!$property->isInitialized($object)) {
                    $property->setValue($object, $value);
                }
            } else {
                $property->setValue($object, $value);
            }
        }
    }

    return deepclone_restore($data['root'], $objects, $data);
}

$profile = new DeepProfile(
    name: 'Ada',
    balance: new DeepMoney(1250, 'EUR'),
    address: new DeepAddress('Main Street 1', 'Utrecht'),
    status: DeepStatus::Active,
    formatter: fn (int $value): int => $value,
    length: strlen(...),
    secret: 'hidden',
    note: 'protected note',
);
$profile->formatter = $profile->makeFormatter(3);
$profile->views = 5;
$profile->method = new DeepMethod(new ReflectionMethod(DeepProfile::class, 'secret'));

$data = deepclone_to_array($profile);
echo 'objects=', count($data['objects']), "\n";

foreach ($data['objects'] as $entry) {
    echo $entry['class'], ' cloneable=', (int) $entry['cloneable'], "\n";
}

$restored = deepclone_from_array($data);

var_dump($restored instanceof DeepProfile);
var_dump($restored->name, $restored->balance->amount, $restored->balance->currency);
print_r($restored->address);
var_dump($restored->status === DeepStatus::Active);
var_dump($restored->views);
$restored->views = 3;
var_dump($restored->views);
var_dump($restored->secret(), $restored->note());
var_dump($restored->method->methodName());

$formatter = new ReflectionFunction($restored->formatter);
var_dump($formatter->isAnonymous(), $formatter->isStatic());
var_dump($formatter->getClosureCalledClass()?->getName(), $formatter->getClosureScopeClass()?->getName());
var_dump($formatter->getClosureThis() === $restored);
var_dump($formatter->getClosureUsedVariables());
var_dump(($restored->formatter)(4));
var_dump(($restored->length)('hello'));
