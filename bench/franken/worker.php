<?php
// FrankenPHP worker mode for the www/ routes: the process stays up and runs each request's script
// in a loop, like BlitzVM's long-lived engine. The scripts declare nothing at top level, so
// re-including them per request is safe.
ignore_user_abort(true);
$www = realpath(__DIR__ . '/../../www');
$handler = static function () use ($www): void {
    $file = realpath($www . parse_url($_SERVER['REQUEST_URI'], PHP_URL_PATH));
    if ($file === false || !str_starts_with($file, $www . '/') || !str_ends_with($file, '.php')) {
        http_response_code(404);
        return;
    }
    header('X-Bench-Worker: 1');
    require $file;
};
while (frankenphp_handle_request($handler)) {
    gc_collect_cycles();
}
