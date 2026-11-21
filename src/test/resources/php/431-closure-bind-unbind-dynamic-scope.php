<?php
// Closure::bind($c, null, null) on static closures and on closures with dynamic scope but no $this
// (the pattern Composer 2.11 autoloaders use): php binds all of them without a warning.
set_error_handler(function ($n, $s) { echo "warning:$s\n"; return true; });
class Loader {
    static function make() {
        return \Closure::bind(static function ($file) { if (false) { require $file; } return 'ok:' . $file; }, null, null);
    }
    function inst() {
        return \Closure::bind(function ($file) { if (false) { include $file; } return 'inst:' . $file; }, null, null);
    }
}
$f = Loader::make();
var_dump(is_callable($f), $f('a'));
$g = (new Loader)->inst();
var_dump(is_callable($g), $g('b'));
class A2 { public $v = 7; function c1() { return function () { $n = 'this'; return isset($$n); }; }
                          function c2() { return function () { extract([]); return 1; }; }
                          function c3() { return function () { return get_defined_vars(); }; }
                          function c4() { return function ($f) { if ($f) include $f; return 2; }; } }
$a = new A2;
foreach (['c1', 'c2', 'c3', 'c4'] as $m) { $b = \Closure::bind($a->$m(), null, null); echo $m, ': ', $b === null ? 'refused' : 'bound', "\n"; }
class WithThis { public $v = 1; function c() { return function () { return $this->v; }; } }
var_dump(\Closure::bind((new WithThis)->c(), null, null));
