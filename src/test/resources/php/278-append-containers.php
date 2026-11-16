<?php
function containers() {
    $s = 'a'; $s .= 'b'; $a = [$s, ['s' => $s]]; $b = $a;
    $a[0] .= 'c'; $a[1]['s'] .= 'd'; $s .= 'e';
    echo json_encode($a), '|', json_encode($b), '|', $s, "\n";
    $object = new stdClass(); $object->s = $s; $clone = clone $object;
    $object->s .= 'f'; echo $object->s, '|', $clone->s, "\n";
    echo json_encode($object), '|', json_encode((array) $object), '|', json_encode(get_object_vars($object)), "\n";
    foreach ($object as $name => $value) { echo $name, '=', $value, "\n"; }
    echo serialize((array) $object), "\n";
    $a = ['x']; $r =& $a[0]; $r .= 'y'; $b = $a; $r .= 'z';
    echo json_encode($a), '|', json_encode($b), "\n";
}
containers();
class AppendAccess implements ArrayAccess {
    public $values = ['s' => 'a', 'nested' => ['s' => 'b']];
    function offsetExists($key): bool { return isset($this->values[$key]); }
    function &offsetGet($key): mixed { echo 'get:', $key, '|'; return $this->values[$key]; }
    function offsetSet($key, $value): void { echo 'set:', $key, '|'; $this->values[$key] = $value; }
    function offsetUnset($key): void { unset($this->values[$key]); }
}
$access = new AppendAccess(); $access['s'] .= 'x'; echo "\n";
$access['nested']['s'] .= 'y'; echo "\n";
echo json_encode($access->values), "\n";
class AppendMagic {
    private $value = 'a';
    function __get($name) { echo 'magic-get|'; return $this->value; }
    function __set($name, $value) { echo 'magic-set|'; $this->value = $value; }
}
$magic = new AppendMagic(); echo ($magic->s .= 'b'), "\n";
