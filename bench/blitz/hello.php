<?php
// Blitz\Http\Server hello, the same body as www/hello.php and bench/swoole/server.php.
// PORT and WORKERS are read from the environment so the load scripts can reuse it.
$port = (int)(getenv('PORT') ?: 18690);
$workers = (int)(getenv('WORKERS') ?: 4);

$server = new Blitz\Http\Server('127.0.0.1', $port);
$server->set(['worker_num' => $workers]);
$server->on('request', function (Blitz\Http\Request $req, Blitz\Http\Response $res) {
    $res->header('Content-Type', 'text/plain');
    $res->end("Hello, World!\n");
});
$server->start();
