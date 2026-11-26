<?php
declare(strict_types=1);

function show(string $label, mixed $value): void {
    echo $label, ': ';
    var_dump($value);
}

class A {}
class B extends A {}
interface I {}

// The subject and class are class-name strings, so strict_types changes no coercion.
set_error_handler(static function (int $errno, string $message): bool {
    echo "deprecated: $errno: $message\n";
    return true;
});
show('string-no-allow', is_a('A', 'A'));
show('string-allow-false', is_a('A', 'A', false));
show('string-allow-true', is_a('A', 'A', true));
show('leading-slash', is_a('\\A', 'A', true));
show('object', is_a(new B(), A::class));
show('object-interface', is_a(new B(), I::class));
restore_error_handler();

error_reporting(0);
$isA = 'is_a';
show('value-string', $isA('A', 'A'));
show('cuf-string', call_user_func('is_a', 'A', 'A'));
show('named-string', is_a(object_or_class: 'A', class: 'A'));
show('named-allow', is_a(object_or_class: 'A', class: 'A', allow_string: true));
error_reporting(E_ALL);
