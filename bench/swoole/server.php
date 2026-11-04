<?php
// Swoole equivalent of www/*.php. Swoole apps are long-running: handlers are
// closures in one process per worker, not files re-executed per request.
use Swoole\Http\Server;

$port = (int)(getenv('PORT') ?: 18301);
$workers = (int)(getenv('WORKERS') ?: 1);
$preempt = getenv('PREEMPT') === '1';

function fib($n) { return $n < 2 ? $n : fib($n - 1) + fib($n - 2); }

$server = new Server('127.0.0.1', $port, SWOOLE_BASE);
$server->set([
    'worker_num' => $workers,
    'enable_coroutine' => true,
    'hook_flags' => SWOOLE_HOOK_ALL,             // usleep/sleep become coroutine yields
    'enable_preemptive_scheduler' => $preempt,   // opt-in 10ms coroutine time slicing
    'log_level' => SWOOLE_LOG_WARNING,
]);

$server->on('request', function ($req, $res) {
    $path = $req->server['request_uri'];
    $q = $req->get ?? [];
    switch ($path) {
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
