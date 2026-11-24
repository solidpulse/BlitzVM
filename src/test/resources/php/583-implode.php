<?php
// implode: mixed scalar types, Stringable objects, nested arrays, keys, errors and the
// removed reversed-argument form.
class ImplodeStringable {
    public function __toString(): string { return 'object'; }
}

function show($label, $value) {
    echo $label, '=', bin2hex($value), "\n";
}

$shared = 'r';
show('mixed', implode(',', [1, 2.5, true, false, null, 'x']));
show('objects', implode('-', [new ImplodeStringable(), 'plain', new ImplodeStringable()]));
show('empty', implode(',', []));
show('emptyglue', implode('', ['a', 'b', 'c']));
show('keys', implode(',', ['k' => 'a', 5 => 'b', 2 => 'c', 'z' => 'd']));
show('one', implode(['only']));
show('numericglue', implode(1, ['a', 'b']));
show('nullglue', implode(null, ['a', 'b']));
show('nested', @implode(',', [[1, 2], 'x', []]));
show('nestedfirst', @implode(',', [[1, 2]]));
show('refs', implode(',', [&$shared, 'b']));

// __toString may modify the array being imploded; the snapshot must not change the output.
$box = [];
$mutable = new class($box) {
    public $target;
    public function __construct(array &$target) { $this->target = &$target; }
    public function __toString(): string {
        $this->target[] = 'added';
        return 'mutable';
    }
};
$box[] = $mutable;
show('mutating', implode('|', $box));
echo 'after=', count($box), "\n";

foreach ([
    'reversed' => fn() => implode(['a', 'b'], ','),
    'arrayfirst' => fn() => implode([], 'x'),
    'notarray' => fn() => implode(',', 'x'),
    'nullarray' => fn() => implode(',', null),
    'missingarray' => fn() => implode(','),
] as $label => $call) {
    try {
        $call();
        echo $label, "=no error\n";
    } catch (Throwable $e) {
        echo $label, '=', get_class($e), ': ', $e->getMessage(), "\n";
    }
}
