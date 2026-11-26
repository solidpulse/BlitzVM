<?php
// An exception thrown from an offset method, with the trace php reports for an engine call.
function frame(array $f): string {
    $fn = $f['function'] ?? '?';
    return ($f['class'] ?? '') . ($f['type'] ?? '') . $fn . ':' . ($f['line'] ?? 0);
}

final class ThrowA implements ArrayAccess {
    public function offsetExists(mixed $o): bool { throw new RuntimeException('exists'); }
    public function offsetGet(mixed $o): mixed { throw new RuntimeException('get'); }
    public function offsetSet(mixed $o, mixed $v): void { throw new RuntimeException('set'); }
    public function offsetUnset(mixed $o): void { throw new RuntimeException('unset'); }
}
final class ThrowB implements ArrayAccess {
    public function offsetExists(mixed $o): bool { throw new RuntimeException('exists'); }
    public function offsetGet(mixed $o): mixed { throw new RuntimeException('get'); }
    public function offsetSet(mixed $o, mixed $v): void { throw new RuntimeException('set'); }
    public function offsetUnset(mixed $o): void { throw new RuntimeException('unset'); }
}

foreach ([new ThrowA(), new ThrowB()] as $o) {
    try { $x = $o['k']; } catch (RuntimeException $e) {
        echo $e->getMessage(), ': ', implode(' | ', array_map('frame', $e->getTrace())), "\n";
    }
    try { isset($o['k']); } catch (RuntimeException $e) {
        echo $e->getMessage(), ': ', implode(' | ', array_map('frame', $e->getTrace())), "\n";
    }
    try { $x = $o['k'] ?? 'default'; } catch (RuntimeException $e) {
        echo $e->getMessage(), ': ', implode(' | ', array_map('frame', $e->getTrace())), "\n";
    }
    try { $o['k'] = 1; } catch (RuntimeException $e) {
        echo $e->getMessage(), ': ', implode(' | ', array_map('frame', $e->getTrace())), "\n";
    }
    try { unset($o['k']); } catch (RuntimeException $e) {
        echo $e->getMessage(), ': ', implode(' | ', array_map('frame', $e->getTrace())), "\n";
    }
}
