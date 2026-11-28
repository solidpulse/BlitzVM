<?php
declare(strict_types=1);

function show(string $label, mixed $value): void {
    echo $label, ': ';
    var_dump($value);
}

show('reporting-int', error_reporting(12345));
show('reporting-null', error_reporting(null));
show('reporting-after-null', error_reporting());

// Null means "leave the level alone" in strict mode too; the fast path hands the
// one-argument shape to the builtin, which owns that rule.
show('reporting-null-again', error_reporting(null));
show('reporting-after-null-again', error_reporting());

show('preg', preg_last_error());
show('timezone', date_default_timezone_get());
show('clear', error_clear_last());
show('time', is_int(time()));

// The value and call_user_func paths behave the same under strict_types.
$fn = 'preg_last_error';
show('preg-value', is_int($fn()));
show('timezone-call-user-func', is_string(call_user_func('date_default_timezone_get')));
show('clear-value', ($f = 'error_clear_last')() === null);
