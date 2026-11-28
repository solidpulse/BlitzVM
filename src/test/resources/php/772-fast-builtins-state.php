<?php

function show(string $label, mixed $value): void {
    echo $label, ': ';
    var_dump($value);
}

// preg_last_error is the request's PCRE error code; a successful match clears it.
show('preg-none', preg_last_error());
@preg_match('/[/', 'x');
show('preg-invalid', preg_last_error());
show('preg-valid', preg_match('/a/', 'a'));
show('preg-cleared', preg_last_error());

// error_reporting() with no argument reads the level; with an argument it sets it.
show('reporting-set-zero', error_reporting(0));
show('reporting-zero', error_reporting());
show('reporting-set-number', error_reporting(12345));
show('reporting-number', error_reporting());
show('reporting-null', error_reporting(null));
show('reporting-after-null', error_reporting());
show('reporting-named', error_reporting(error_level: 0));
show('reporting-named-value', error_reporting());

// date_default_timezone_get follows date_default_timezone_set.
show('timezone-default', date_default_timezone_get());
show('timezone-set', date_default_timezone_set('Europe/Paris'));
show('timezone-paris', date_default_timezone_get());
show('timezone-set-back', date_default_timezone_set('UTC'));
show('timezone-utc', date_default_timezone_get());

// error_clear_last is a no-op in this runtime, as its builtin implementation is.
show('clear-last', error_clear_last());

// time is an int close to microtime(true), and keeps its shape through the value paths.
$now = time();
show('time-int', is_int($now));
show('time-plausible', $now > 1600000000 && $now <= (int) microtime(true) + 1);
$fn = 'time';
show('time-value', is_int($fn()));
show('time-call-user-func', is_int(call_user_func('time')));
show('time-call-user-func-array', is_int(call_user_func_array('time', [])));
show('preg-last-value', is_int(($f = 'preg_last_error')()));
show('timezone-value', is_string(($f = 'date_default_timezone_get')()));
show('clear-last-value', ($f = 'error_clear_last')() === null);

// The no-argument builtins reject extra arguments through the builtin, not the fast path.
foreach ([
    'time-too-many' => static fn () => time(1),
    'preg-too-many' => static fn () => preg_last_error(1),
    'timezone-too-many' => static fn () => date_default_timezone_get(1),
    'clear-too-many' => static fn () => error_clear_last(1),
    'unknown-named' => static fn () => time(foo: 1),
] as $label => $run) {
    try {
        $run();
        show($label, 'no error');
    } catch (Error $e) {
        show($label, $e->getMessage());
    }
}
