<?php
// Dynamic scope on bytecode: $$x, compact, extract, get_defined_vars, include into a
// function, global/static in a symbol-table function, $$this.
function dyn($name, $v) {
    $$name = $v;
    $local = "L";
    echo $$name, " ", $local, "\n";
    $arr = compact('name', 'local', 'missing');
    print_r($arr);
    ${'x' . 'y'}[] = 5;
    ${'x' . 'y'}[] = 6;
    echo count($xy), "\n";
    unset($$name);
    echo isset($$name) ? "set" : "unset", "\n";
    return get_defined_vars();
}
print_r(dyn("foo", 42));
function ex(array $args) {
    $title = "T";
    extract($args, EXTR_SKIP);
    echo "$title $a $b\n";
    $n = extract(['c' => 3, 'title' => 'X'], EXTR_PREFIX_ALL, 'p');
    echo "$n $p_c $p_title\n";
    include __DIR__ . '/inc/318-local-scope.php';
    echo $fromInclude, "\n";
    $ref = $args;
    $ref['z'] = 1;
    $i = 0; $i++; $i += 5;
    $s = "a"; $s .= "b";
    echo "$i $s ", count($args), "\n";
}
ex(['a' => 1, 'b' => 2, 'title' => 'nope', 'k' => 'K']);
$g = "global-g";
function gl() { global $g; $k = 'g'; static $cnt = 0; $cnt++; echo $g, " ", $$k ?? 'none', " $cnt\n"; }
gl(); gl();
class C { public $p = 7; function m($n) { $v = 'this'; echo $this->p, " ", $$v->p, "\n"; $z = 'p'; $$z = 9; echo $p, "\n"; } }
(new C)->m(1);
function byref(&$out, $name) { $$name = 'dyn'; $out = $$name . '!'; }
byref($r, 'q'); echo $r, "\n";
function compact_plain() { $a = 1; $b = null; return compact('a', 'b', 'c'); }
print_r(compact_plain());
