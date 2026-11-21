<?php
// compact() with computed names in functions without a symbol table: the names resolve against
// the function's locals (parameters, assigned, by-reference, unset, never assigned).
set_error_handler(function ($n, $s) { echo "warning:$s\n"; return true; });
function query_like($fields, $where = 'WHERE 1=1') {
    $join = '';
    $groupby = 'ID';
    $orderby = null;
    $limits = 'LIMIT 10';
    $pieces = array('where', 'groupby', 'join', 'orderby', 'distinct', 'fields', 'limits');
    $clauses = compact($pieces);
    var_dump($clauses);
    $distinct = 'DISTINCT';
    var_dump(compact($pieces));
    unset($join);
    var_dump(compact('join', $pieces[1], ['limits', ['fields']]));
    return $clauses;
}
query_like('*');

function by_ref(&$out) {
    $alias = &$out;
    $alias = 'changed';
    $name = 'alias';
    var_dump(compact($name, 'out'));
    $n = 5;
    $n++;
    $s = 'a';
    $s .= 'b';
    $list = ['n', 's'];
    var_dump(compact($list));
}
$x = 'orig';
by_ref($x);
var_dump($x);

class Holder {
    private $v = 3;
    function names() {
        $local = $this->v * 2;
        $which = ['local', 'this', 'missing'];
        $r = compact($which);
        var_dump(array_keys($r), $r['local'], $r['this'] instanceof Holder);
    }
}
(new Holder)->names();

$closure = function ($a) {
    $b = $a + 1;
    $names = ['a', 'b', 'c'];
    return compact($names);
};
var_dump($closure(1));

function bad_arg() {
    $v = 1;
    var_dump(compact(42, 'v'));
}
bad_arg();

function with_extract($arr) {
    extract($arr);
    $names = ['p', 'q'];
    var_dump(compact($names));
}
with_extract(['p' => 1, 'q' => 2]);

function gen() {
    $g = 'gen';
    $names = ['g'];
    yield compact($names);
}
foreach (gen() as $v) { var_dump($v); }

$top = 'file';
$names = ['top'];
var_dump(compact($names));
