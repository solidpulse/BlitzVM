<?php
date_default_timezone_set('Asia/Tokyo');
mb_internal_encoding('ISO-8859-1');
mb_substitute_character(0x3F);

function lazy_autoloader($name)
{
    if ($name === 'LazyClass') {
        eval('class LazyClass { const VALUE = 42; }');
        echo "autoloaded ", $name, "\n";
    }
}

spl_autoload_register('lazy_autoloader');

set_error_handler(function ($errno, $errstr) {
    echo "error handler: ", $errstr, "\n";
    return true;
});

set_exception_handler(function ($exception) {
    echo "exception handler: ", $exception->getMessage(), "\n";
});

$shutdownMessage = new stdClass();
$shutdownMessage->text = 'shutdown';
register_shutdown_function(function () use ($shutdownMessage) {
    echo $shutdownMessage->text, "\n";
});
