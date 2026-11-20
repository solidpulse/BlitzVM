<?php
#[Attribute(Attribute::TARGET_METHOD | Attribute::TARGET_CLASS)]
class Route {
    public function __construct(public string $path, public string $method = 'GET') {}
}

#[Route('/controller')]
class UserController {
    #[Route('/users', 'GET')]
    public function index(): string { return 'index'; }

    #[Route('/users', 'POST')]
    public function create(): string { return 'create'; }

    #[Route('/users/{id}', 'GET')]
    public function show(int $id = 1): string { return 'show:' . $id; }

    public function health(): string { return 'health'; }
}

function routes(string $class): array {
    $reflection = new ReflectionClass($class);
    $result = [];
    foreach ($reflection->getMethods(ReflectionMethod::IS_PUBLIC) as $method) {
        foreach ($method->getAttributes(Route::class, ReflectionAttribute::IS_INSTANCEOF) as $attribute) {
            $route = $attribute->newInstance();
            $result[] = $route->method . ' ' . $route->path . ' -> ' . $method->getName();
        }
    }
    return $result;
}

function dispatch(string $class, string $httpMethod, string $path): string {
    $reflection = new ReflectionClass($class);
    $controller = $reflection->newInstance();
    foreach ($reflection->getMethods(ReflectionMethod::IS_PUBLIC) as $method) {
        foreach ($method->getAttributes(Route::class, ReflectionAttribute::IS_INSTANCEOF) as $attribute) {
            $route = $attribute->newInstance();
            if ($route->method !== $httpMethod || $route->path !== $path) {
                continue;
            }
            $arguments = [];
            foreach ($method->getParameters() as $parameter) {
                $arguments[] = $parameter->isDefaultValueAvailable() ? $parameter->getDefaultValue() : null;
            }
            return (string)$method->invokeArgs($controller, $arguments);
        }
    }
    return '404';
}

echo implode("\n", routes(UserController::class)), "\n";
echo 'GET /users => ', dispatch(UserController::class, 'GET', '/users'), "\n";
echo 'POST /users => ', dispatch(UserController::class, 'POST', '/users'), "\n";
echo 'GET /users/{id} => ', dispatch(UserController::class, 'GET', '/users/{id}'), "\n";
echo 'GET /missing => ', dispatch(UserController::class, 'GET', '/missing'), "\n";

$classRoute = (new ReflectionClass(UserController::class))->getAttributes(Route::class)[0]->newInstance();
echo 'class route=', $classRoute->method, ' ', $classRoute->path, "\n";
