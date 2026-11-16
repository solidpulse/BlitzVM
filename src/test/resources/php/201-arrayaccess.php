<?php
class Dictionary implements ArrayAccess, Countable {
    protected $values = ['a' => 2, 'nil' => null, 0 => 'first'];
    public function offsetExists($k): bool { echo 'exists:', $k, '|'; return array_key_exists(strtolower((string)$k), $this->values); }
    public function offsetGet($k): mixed { echo 'get:', $k, '|'; return $this->values[strtolower((string)$k)]; }
    public function offsetSet($k, $v): void {
        echo 'set:', $k === null ? 'append' : $k, '|';
        if ($k === null) { $this->values[] = $v; } else { $this->values[strtolower((string)$k)] = $v; }
    }
    public function offsetUnset($k): void { echo 'unset:', $k, '|'; unset($this->values[strtolower((string)$k)]); }
    public function count(): int { return count($this->values); }
}
$d = new Dictionary;
echo $d['A'], '\n';
var_dump(isset($d['nil']), isset($d['absent']), empty($d['a']), $d['nil'] ?? 'fallback');
$d['A'] += 3;
$d[] = 'last';
echo $d['a'], ':', count($d), '\n';
['a' => $a, 0 => $b] = $d;
echo "$a:$b\n";
unset($d['a']);
var_dump(isset($d['a']));
class RefDictionary implements ArrayAccess {
    public $values = ['x' => ['y' => 1], 'n' => 5];
    public function offsetExists($k): bool { return isset($this->values[$k]); }
    public function &offsetGet($k): mixed { return $this->values[$k]; }
    public function offsetSet($k, $v): void { $this->values[$k] = $v; }
    public function offsetUnset($k): void { unset($this->values[$k]); }
}
$r = new RefDictionary;
$r['x']['y'] = 9;
$r['n']++;
$v = &$r['n'];
$v += 2;
echo json_encode($r->values), '\n';
set_error_handler(function ($errno, $message) { echo "notice:$errno:$message\n"; return true; });
$d = new Dictionary;
$d['a']++;
echo $d['a'], '\n';
class NestedValueAccess implements ArrayAccess {
    public $value = ['n' => 1];
    public function offsetExists($k): bool { return true; }
    public function offsetGet($k): mixed { echo 'nested-get|'; return $this->value; }
    public function offsetSet($k, $v): void { echo 'unexpected-set|'; }
    public function offsetUnset($k): void {}
}
$nested = new NestedValueAccess;
$nested['a']['n'] += 4;
echo json_encode($nested->value), "\n";
