<?php
set_error_handler(function (int $number, string $message): bool {
    echo 'WARN ', $message, "\n";
    return true;
});

var_dump(get_cfg_var('precision'), get_cfg_var('xdebug.file_link_format'), get_cfg_var('nope'));
var_dump((new ReflectionFunction('get_cfg_var'))->getNumberOfParameters());

var_dump(getenv('BLITZ_ENV_TEST'));
var_dump(putenv('BLITZ_ENV_TEST=hello'));
var_dump(getenv('BLITZ_ENV_TEST'));
var_dump(putenv('BLITZ_ENV_TEST'));
var_dump(getenv('BLITZ_ENV_TEST'));
$all = getenv();
var_dump($all['BLITZ_ENV_TEST'] ?? 'absent');

var_dump(gc_enabled());
gc_disable();
var_dump(gc_enabled());
gc_enable();
var_dump(gc_enabled());
var_dump(is_int(gc_collect_cycles()), is_int(gc_mem_caches()));
$status = gc_status();
var_dump(array_keys($status), $status['running'], $status['protected'], $status['full'], is_int($status['threshold']));
