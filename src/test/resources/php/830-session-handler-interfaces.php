<?php
// The session extension's handler interfaces exist so userland handlers can implement them.
foreach (['SessionHandlerInterface', 'SessionIdInterface', 'SessionUpdateTimestampHandlerInterface'] as $name) {
    $r = new ReflectionClass($name);
    echo $name, ' ', $r->isInterface() ? 'interface' : 'class', "\n";
    foreach ($r->getMethods() as $method) {
        $params = [];
        foreach ($method->getParameters() as $param) {
            $params[] = '$' . $param->getName();
        }
        echo '  ', $method->getName(), '(', implode(', ', $params), ")\n";
    }
}
class Handler implements SessionHandlerInterface, SessionIdInterface, SessionUpdateTimestampHandlerInterface
{
    public function open(string $path, string $name): bool { return true; }
    public function close(): bool { return true; }
    public function read(string $id): string|false { return ''; }
    public function write(string $id, string $data): bool { return true; }
    public function destroy(string $id): bool { return true; }
    public function gc(int $max_lifetime): int|false { return 0; }
    public function create_sid(): string { return 'sid'; }
    public function validateId(string $id): bool { return true; }
    public function updateTimestamp(string $id, string $data): bool { return true; }
}
$handler = new Handler();
var_dump($handler instanceof SessionHandlerInterface, $handler->open('/tmp', 'PHPSESSID'),
    $handler->read('x'), $handler->gc(120));
