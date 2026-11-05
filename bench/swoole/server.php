<?php
// Swoole equivalent of www/*.php. Swoole apps are long-running: handlers are
// closures in one process per worker, not files re-executed per request.
use Swoole\Http\Server;

$port = (int)(getenv('PORT') ?: 18301);
$workers = (int)(getenv('WORKERS') ?: 1);
$preempt = getenv('PREEMPT') === '1';

function fib($n) { return $n < 2 ? $n : fib($n - 1) + fib($n - 2); }

// Same kernels as www/compute.php, loaded once at start-up.
$KERNELS_ONLY = true;
require '/www/compute.php';

$server = new Server('127.0.0.1', $port, SWOOLE_BASE);
$server->set([
    'worker_num' => $workers,
    'enable_coroutine' => true,
    'hook_flags' => SWOOLE_HOOK_ALL,             // usleep/sleep become coroutine yields
    'enable_preemptive_scheduler' => $preempt,   // opt-in 10ms coroutine time slicing
    'log_level' => SWOOLE_LOG_WARNING,
    'document_root' => '/www',                   // static files, like nginx in front of FPM
    'enable_static_handler' => true,
]);

$server->on('request', function ($req, $res) {
    $path = $req->server['request_uri'];
    $q = $req->get ?? [];
    switch ($path) {
        case '/':
        case '/index.php':
            // Same output as www/index.php.
            $name = htmlspecialchars($q['name'] ?? 'World');
            $res->header('X-Request-Id', getmypid() . '-' . hrtime(true));
            $items = '';
            foreach (['method' => $req->server['request_method'], 'uri' => $req->server['request_uri'] . (isset($req->server['query_string']) ? '?' . $req->server['query_string'] : '')] as $k => $v) {
                $items .= "    <li>$k: " . htmlspecialchars($v) . "</li>\n";
            }
            $res->end("<!doctype html>\n<html>\n<head><title>Hello from PHP on Truffle</title></head>\n<body>\n"
                . "  <h1>Hello, $name!</h1>\n  <p>Served by swoole (" . php_sapi_name() . ") at " . date('Y-m-d H:i:s') . ".</p>\n"
                . "  <ul>\n$items  </ul>\n</body>\n</html>\n");
            return;
        case '/hello.php':
            $res->end("Hello, World!\n");
            return;
        case '/spin.php':
            $seconds = (float)($q['s'] ?? 3);
            $end = microtime(true) + $seconds;
            $i = 0;
            while (microtime(true) < $end) { $i++; }
            $res->end("spun $i iterations in {$seconds}s\n");
            return;
        case '/sleep.php':
            usleep((int)($q['ms'] ?? 500) * 1000);
            $res->end("slept\n");
            return;
        case '/compute.php':
            $res->end(run_kernel($q['k'] ?? 'mandelbrot', (int)($q['n'] ?? 0)));
            return;
        case '/fib.php':
            $n = (int)($q['n'] ?? 25);
            $t = microtime(true);
            $r = fib($n);
            $res->end(sprintf("fib(%d) = %d in %.1f ms\n", $n, $r, (microtime(true) - $t) * 1000));
            return;
        default:
            $res->status(404);
            $res->end("not found\n");
    }
});
echo "swoole listening on $port workers=$workers preempt=" . ($preempt ? 'on' : 'off') . "\n";
$server->start();
