<?php
// compact()/extract() flags, computed names and undefined variables.
set_error_handler(function ($no, $msg) { echo "warning: $msg\n"; return true; });

$alpha = 1;
$beta = 'two';
$gamma = [3];
$prefixed_one = 'p1';
$prefixed_two = 'p2';

print_r(compact('alpha', 'beta'));
print_r(compact(['alpha', 'gamma']));
print_r(compact('alpha', 'missing'));
print_r(compact('prefixed_one'));
var_dump(compact(1, null, 'alpha'));

$target = ['alpha' => 'old', 'extra' => 'keep'];
print_r(extract($target, EXTR_OVERWRITE));
echo $alpha, " ", $extra, "\n";

$alpha = 1;
$target = ['alpha' => 'skip', 'other' => 'new'];
extract($target, EXTR_SKIP);
echo $alpha, " ", $other, "\n";

unset($other);

$target = ['alpha' => 'same', 'extra2' => 'e2'];
extract($target, EXTR_PREFIX_SAME, 'p');
echo $alpha, " ", $p_alpha, " ", $extra2, "\n";

$target = ['one' => 'O', 'two' => 'T'];
extract($target, EXTR_PREFIX_ALL, 'x');
echo $x_one, " ", $x_two, "\n";

$target = ['alpha' => 'if', 'brandnew' => 'nope'];
extract($target, EXTR_IF_EXISTS);
echo $alpha, "\n";
var_dump(isset($brandnew));

$target = ['prefixed_one' => 'ref', 'new_one' => 'n2'];
extract($target, EXTR_PREFIX_IF_EXISTS, 'q');
echo $q_prefixed_one ?? 'unset', "\n";
var_dump(isset($q_new_one));

$target = ['1' => 'numeric', 'has space' => 'bad', 'ok' => 'fine'];
extract($target, EXTR_PREFIX_INVALID, 'v');
var_dump(isset($v_1), isset($v_has_space), isset($v_ok));
echo $ok, "\n";

function scoped() {
    $local = 'L';
    print_r(compact('local'));
    $values = ['local' => 'changed', 'made' => 'M'];
    extract($values);
    return "$local $made";
}
echo scoped(), "\n";
