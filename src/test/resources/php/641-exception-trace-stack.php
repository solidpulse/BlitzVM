<?php
function mk641($why) { return new RuntimeException($why); }
function show641(Throwable $e) {
    echo get_class($e), ' ', $e->getMessage(), ' @', $e->getLine(), "\n";
    foreach ($e->getTrace() as $i => $f) {
        echo "  #$i ", isset($f['file']) ? basename($f['file']) . ':' . $f['line'] : '[internal]', ' ',
            ($f['class'] ?? '') . ($f['type'] ?? '') . $f['function'], ' [', implode(',', array_keys($f)), "]\n";
    }
    $s = preg_replace('~/[^ :()]*/~', '', $e->getTraceAsString());
    echo preg_replace('/\{closure:[^}]*\}/', '{closure}', $s), "\n";
}
class K {
    public function m() { return mk641('method'); }
    public static function s() { return array_map(fn($x) => mk641('arrow in array_map'), [1])[0]; }
    public function viaCall() { return call_user_func([$this, 'm']); }
    public function __toString(): string { throw new LogicException('toString'); }
}
show641(mk641('top'));
show641((new K)->m());
show641(K::s());
show641((new K)->viaCall());
show641(include __DIR__ . '/inc/641-ex-include.php');
$b = Closure::bind(function () { return mk641('bound'); }, new K, K::class);
show641($b());
function rethrow() { try { throw new DomainException('inner'); } catch (DomainException $e) { throw new RuntimeException('outer', 0, $e); } }
try { rethrow(); } catch (RuntimeException $e) { show641($e); show641($e->getPrevious()); }
function gen641() { yield mk641('generator'); }
foreach (gen641() as $e) { show641($e); }
try { intdiv(1, 0); } catch (DivisionByZeroError $e) { show641($e); }
try { array_map(function ($x) { return strlen(); }, [1]); } catch (ArgumentCountError $e) { show641($e); }
$arr = [3, 1, 2];
try { usort($arr, function ($a, $b) { throw new Exception('in usort'); }); } catch (Exception $e) { show641($e); }
