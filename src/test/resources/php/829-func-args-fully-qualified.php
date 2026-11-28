<?php
// Fully qualified \func_num_args() and \func_get_args() are still the language's own forms.
function countArgs(): array {
    return [\func_num_args(), \func_get_args()];
}
var_dump(countArgs(1, 2, 3));
class C {
    public function method(): void {
        var_dump(\func_num_args(), \func_get_arg(0), \func_get_args());
    }
}
(new C)->method('x', 'y');
$closure = function (...$values) { return [\func_num_args(), \func_get_args()]; };
var_dump($closure(4, 5));
$arrow = fn ($a) => [\func_num_args(), \func_get_args()];
var_dump($arrow('only'));
