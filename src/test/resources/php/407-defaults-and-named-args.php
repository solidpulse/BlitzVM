<?php
// Defaults and named arguments for typed parameters; implicit nullability in an include.
function strip($s) {
    return str_replace(__DIR__ . '/', '', $s);
}

set_error_handler(function (int $errno, string $message) {
    echo 'DEPRECATED: ', $message, "\n";
    return true;
});

function defaults(int $number = 5, string $text = 'x', ?array $list = null) {
    echo $number, '|', $text, '|', $list === null ? 'null' : count($list), "\n";
}

defaults();
defaults(number: '7');
defaults(text: 12, number: 3);
defaults(list: [1, 2]);
try { defaults(number: 'x'); } catch (TypeError $e) { echo strip($e->getMessage()), "\n"; }

function namedVariadic(int $first, string ...$rest) {
    echo $first, ':', implode(',', $rest), "\n";
}
namedVariadic(1, 'a', 'b');
namedVariadic(first: '2', rest: 3);

class Options {
    public function configure(int $count = 1, bool $enabled = false) {
        var_dump($count, $enabled);
    }
}
(new Options())->configure(count: '9');
(new Options())->configure(enabled: '1');

include __DIR__ . '/inc/implicit-nullable.php';
implicitNullable();
implicitNullable('12');
try { implicitNullable([]); } catch (TypeError $e) { echo strip($e->getMessage()), "\n"; }

$closure = function (int $value = 4, string ...$more) {
    echo $value, ':', implode(',', $more), "\n";
};
$closure();
$closure(value: '6', more: 7);
