<?php

function show(string $label, mixed $value): void {
    echo $label, ': ';
    var_dump($value);
}

class A {}
class B extends A {}
interface I {}
class Impl implements I {}

// A string subject without $allow_string is deprecated and always false; the fast
// path keeps the deprecation, so a handler sees the same level and wording.
set_error_handler(static function (int $errno, string $message): bool {
    echo "deprecated: $errno: $message\n";
    return true;
});
show('string-no-allow', is_a('A', 'A'));
show('string-allow-false', is_a('A', 'A', false));
show('string-allow-true', is_a('A', 'A', true));
show('string-interface', is_a(Impl::class, I::class, true));
show('string-missing-subject', is_a('NoSuchClass', A::class, true));
show('object', is_a(new B(), A::class));
show('object-interface', is_a(new Impl(), I::class));
show('object-missing-target', is_a(new B(), 'NoSuchClass'));
restore_error_handler();

// Suppressed deprecations still return false without output.
error_reporting(0);
show('suppressed-string', is_a('A', 'A'));
show('suppressed-allow-false', is_a('A', 'A', false));
error_reporting(E_ALL);

// Named arguments bind the same way and keep the deprecated shape. Deprecations stay
// suppressed so the fixture does not depend on where a diagnostic is printed.
error_reporting(0);
show('named-string', is_a(object_or_class: 'A', class: 'A'));
show('named-allow-true', is_a(object_or_class: 'A', class: 'A', allow_string: true));

// The value and call_user_func paths still reach the builtin.
$isA = 'is_a';
show('value-string', $isA('A', 'A'));
show('value-object', $isA(new B(), A::class));
show('cuf-string', call_user_func('is_a', 'A', 'A'));
show('cuf-allow', call_user_func('is_a', 'A', 'A', true));
error_reporting(E_ALL);
