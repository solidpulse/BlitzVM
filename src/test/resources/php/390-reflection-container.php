<?php
interface Logger { public function log(string $message): string; }
class FileLogger implements Logger {
    public function log(string $message): string { return 'file:' . $message; }
}
class Clock {
    public function now(): string { return '2026-01-01'; }
}
class Repository {
    public function __construct(private Logger $logger, private Clock $clock) {}
    public function find(int $id): string { return $this->logger->log($this->clock->now() . '#first') . ':' . $id; }
}
class Service {
    public function __construct(
        private Repository $repository,
        private string $prefix = 'svc',
        private ?Logger $optionalLogger = null
    ) {}
    public function run(): string { return $this->prefix . '=' . $this->repository->find(3); }
}
class MissingDependency {
    public function __construct(Logger $logger, string $required) {}
}

function resolve(string $class, array $bindings = []) {
    $key = strtolower($class);
    foreach ($bindings as $abstract => $concrete) {
        if (strtolower($abstract) === $key) {
            return resolve($concrete, $bindings);
        }
    }
    $reflection = new ReflectionClass($class);
    $constructor = $reflection->getConstructor();
    if ($constructor === null) {
        return $reflection->newInstance();
    }
    $arguments = [];
    foreach ($constructor->getParameters() as $parameter) {
        $type = $parameter->getType();
        if ($type instanceof ReflectionNamedType && !$type->isBuiltin()) {
            $arguments[] = resolve($type->getName(), $bindings);
            continue;
        }
        if ($type instanceof ReflectionNamedType && $type->allowsNull()) {
            $arguments[] = null;
            continue;
        }
        if ($parameter->isDefaultValueAvailable()) {
            $arguments[] = $parameter->getDefaultValue();
            continue;
        }
        throw new RuntimeException('Cannot resolve $' . $parameter->getName() . ' of ' . $class);
    }
    return $reflection->newInstanceArgs($arguments);
}

$bindings = [Logger::class => FileLogger::class];
$service = resolve(Service::class, $bindings);
echo get_class($service), '|', $service->run(), "\n";
try {
    resolve(MissingDependency::class, $bindings);
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}

$reflection = new ReflectionClass(Service::class);
$constructor = $reflection->getConstructor();
echo 'constructor parameters=';
foreach ($constructor->getParameters() as $parameter) {
    echo $parameter->getName(), ':', $parameter->getType(), ':', (int)$parameter->isOptional(), ',';
}
echo "\n";
