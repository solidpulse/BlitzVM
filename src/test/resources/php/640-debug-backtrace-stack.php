<?php
function bt640($label, $limit = 0) {
    $out = [];
    foreach (debug_backtrace(DEBUG_BACKTRACE_IGNORE_ARGS, $limit) as $f) {
        $out[] = (isset($f['file']) ? basename($f['file']) : '-') . ':' . ($f['line'] ?? '-') . ' '
            . ($f['class'] ?? '') . ($f['type'] ?? '') . $f['function']
            . ' [' . implode(',', array_keys($f)) . ']';
    }
    echo $label, "\n  ", implode("\n  ", $out), "\n";
}
trait Tr { public function viaTrait() { bt640('trait method'); } public static function staticTrait() { bt640('static trait'); } }
abstract class Base { public function inherited() { bt640('inherited'); } }
class A extends Base {
    use Tr;
    public function inst() { bt640('instance'); }
    public static function stat() { bt640('static'); }
    public function __call($n, $a) { bt640('__call ' . $n); }
    public static function __callStatic($n, $a) { bt640('__callStatic ' . $n); }
    public function viaClosure() { $f = function () { bt640('closure in method'); }; $f(); }
    public function viaStaticClosure() { $f = static fn() => bt640('static arrow'); $f(); }
    public function gen() { yield 1; bt640('generator'); yield 2; }
    public function limited() { $this->deeper(); }
    private function deeper() { bt640('limit 1', 1); bt640('limit 2', 2); bt640('limit 0'); }
}
function outer() { inner(); }
function inner() { bt640('function chain'); }
outer();
$a = new A();
$a->inst();
A::stat();
$a->viaTrait();
A::staticTrait();
$a->inherited();
$a->missing();
A::missingStatic();
$a->viaClosure();
$a->viaStaticClosure();
foreach ($a->gen() as $v) {}
$a->limited();
array_map(function ($x) { bt640('array_map callback'); }, [1]);
array_map([$a, 'inst'], [1]);
call_user_func('outer');
call_user_func_array([A::class, 'stat'], []);
$arr = [2, 1]; usort($arr, function ($x, $y) { static $once = false; if (!$once) { $once = true; bt640('usort callback'); } return $x <=> $y; });
include __DIR__ . '/inc/640-bt-include.php';
(function () { bt640('iife'); })();
bt640('top level');
try { (function () { throw new RuntimeException('x'); })(); } catch (RuntimeException $e) { bt640('after catch'); }
function thrower() { throw new LogicException('y'); }
function catcher() { try { thrower(); } catch (LogicException $e) { echo count($e->getTrace()), " frames in trace\n"; bt640('in catcher'); } }
catcher();
$bound = Closure::bind(function () { bt640('bound closure'); }, $a, A::class);
$bound();
echo json_encode(array_map(fn($f) => $f['function'], debug_backtrace())), "\n";
