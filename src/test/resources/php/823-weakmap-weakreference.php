<?php
// WeakMap keeps object keys weakly and remembers insertion order; WeakReference is its single
// object companion. Errors are php's exact ones for non-object keys and missing keys.
$w = new WeakMap;
$a = new stdClass;
$b = new stdClass;
$w[$a] = 'a';
$w[$b] = 'b';
var_dump(count($w), $w[$a], isset($w[$b]), isset($w[new stdClass]));
foreach ($w as $key => $value) {
    echo get_class($key), ' => ', $value, "\n";
}
$w[$a] = null;
var_dump($w->offsetExists($a), isset($w[$a]), count($w));
$w[$a] = false;
var_dump($w->offsetExists($a));
unset($w[$b]);
var_dump(count($w));
try { $w['x'] = 1; } catch (Throwable $e) { echo get_class($e), ': ', $e->getMessage(), "\n"; }
try { echo $w['x']; } catch (Throwable $e) { echo get_class($e), ': ', $e->getMessage(), "\n"; }
try { unset($w['x']); } catch (Throwable $e) { echo get_class($e), ': ', $e->getMessage(), "\n"; }
try { var_dump(isset($w['x'])); } catch (Throwable $e) { echo get_class($e), ': ', $e->getMessage(), "\n"; }
try {
    echo $w[$b];
} catch (Throwable $e) {
    echo get_class($e), ': ', preg_replace('/#\d+/', '#id', $e->getMessage()), "\n";
}
try { $w->offsetSet(); } catch (Throwable $e) { echo get_class($e), ': ', $e->getMessage(), "\n"; }
try { $w->offsetGet($a, 2); } catch (Throwable $e) { echo get_class($e), ': ', $e->getMessage(), "\n"; }
try { $w->count(1); } catch (Throwable $e) { echo get_class($e), ': ', $e->getMessage(), "\n"; }
$c = clone $w;
var_dump(count($c), $c[$a]);
$w[$a] = 'new';
var_dump($c[$a]);
$d = new stdClass;
$c[$d] = 'd';
var_dump(count($w), count($c));
echo get_class($w->getIterator()), "\n";
var_dump($w instanceof ArrayAccess, $w instanceof Countable, $w instanceof IteratorAggregate,
    $w instanceof Traversable);
$r = new ReflectionClass('WeakMap');
var_dump($r->isFinal(), $r->getInterfaceNames());
print_r(get_class_methods('WeakMap'));
try { serialize($w); } catch (Throwable $e) { echo get_class($e), ': ', $e->getMessage(), "\n"; }
var_dump((array) $w, json_encode($w));

// php iterates the live map: a value changed and a key added in the body are both seen.
$m = new WeakMap;
$x = new stdClass;
$y = new stdClass;
$m[$x] = 1;
$m[$y] = 2;
foreach ($m as $key => $value) {
    echo $value, "\n";
    if ($value === 1) {
        $m[$y] = 3;
        $z = new stdClass;
        $m[$z] = 4;
    }
}
var_dump(count($m));

$o = new stdClass;
$ref = WeakReference::create($o);
var_dump($ref->get() === $o, $ref->get() instanceof stdClass);
$r2 = new ReflectionClass('WeakReference');
var_dump($r2->isFinal());
print_r(get_class_methods('WeakReference'));
try { WeakReference::create('x'); } catch (Throwable $e) { echo get_class($e), ': ', $e->getMessage(), "\n"; }
try { new WeakReference(); } catch (Throwable $e) { echo get_class($e), ': ', $e->getMessage(), "\n"; }
try { WeakReference::create(); } catch (Throwable $e) { echo get_class($e), ': ', $e->getMessage(), "\n"; }
try { serialize($ref); } catch (Throwable $e) { echo get_class($e), ': ', $e->getMessage(), "\n"; }
