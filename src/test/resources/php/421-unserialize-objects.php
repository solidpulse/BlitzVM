<?php
class VisibleBase {
    public $pub = 1;
    protected $prot = 2;
    private $priv = 3;
    private $same = 'base';
}
class VisibleChild extends VisibleBase {
    private $same = 'child';
}
class TypedProps {
    public int $i;
    public string $s;
    public float $f;
    public bool $b;
    public ?array $a;
}
class DynamicAllowed {
    public $x = 1;
}
class Waker {
    public $a = 1;
    public $b = 2;
    public function __wakeup() {
        echo "wakeup a={$this->a}\n";
    }
}
class Unserializer {
    public $a = 1;
    public function __unserialize(array $data): void {
        echo 'unserialize keys: ', bin2hex(implode('|', array_keys($data))), "\n";
        $this->a = 9;
    }
    public function __wakeup() {
        echo "should not wake\n";
    }
}

set_error_handler(function ($no, $msg) {
    echo "ERR[$no]: $msg\n";
    return true;
});

function field($name, $value) {
    return 's:' . strlen($name) . ':"' . $name . '";' . serialize($value);
}

$payload = 'O:12:"VisibleChild":6:{' . field('pub', 10)
    . field("\0*\0prot", 11)
    . field("\0VisibleBase\0priv", 12)
    . field("\0VisibleBase\0same", 'base')
    . field("\0VisibleChild\0same", 'child')
    . field('new', 13) . '}';
$value = unserialize($payload);
echo serialize($value), "\n";
echo $value->pub, ' ', $value->new, "\n";

echo serialize(unserialize(serialize(new Waker()))), "\n";
$restored = unserialize('O:12:"Unserializer":3:{' . field('a', 5)
    . field("\0*\0p", 6) . field("\0Unserializer\0q", 7) . '}');
echo $restored->a, "\n";

foreach ([
    'int from string' => 'O:10:"TypedProps":1:{s:1:"i";s:1:"5";}',
    'int from float' => 'O:10:"TypedProps":1:{s:1:"i";d:5.7;}',
    'float from int' => 'O:10:"TypedProps":1:{s:1:"f";i:5;}',
    'bool from int' => 'O:10:"TypedProps":1:{s:1:"b";i:0;}',
    'array from string' => 'O:10:"TypedProps":1:{s:1:"a";s:1:"x";}',
] as $label => $data) {
    echo "$label\n";
    try {
        echo serialize(unserialize($data)), "\n";
    } catch (Throwable $e) {
        echo get_class($e), ': ', $e->getMessage(), "\n";
    }
}

$dynamic = serialize(new DynamicAllowed());
echo unserialize($dynamic, ['allowed_classes' => ['dynamicallowed']]) instanceof DynamicAllowed ? 'allowed' : 'denied', "\n";
echo get_class(unserialize($dynamic, ['allowed_classes' => false])), "\n";
echo get_class(unserialize($dynamic, ['allowed_classes' => ['Other']])), "\n";
echo get_class(unserialize($dynamic, ['allowed_classes' => true])), "\n";
foreach ([
    ['allowed_classes' => 'x'],
    ['allowed_classes' => [1]],
    ['max_depth' => -1],
    ['max_depth' => '2'],
    ['max_depth' => true],
] as $options) {
    echo json_encode($options), "\n";
    try {
        var_dump(unserialize($dynamic, $options));
    } catch (Throwable $e) {
        echo get_class($e), ': ', $e->getMessage(), "\n";
    }
}
try {
    unserialize($dynamic, 'x');
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}

$deep = new stdClass();
$deep->a = new stdClass();
$deep->a->b = [1, [2]];
$deepData = serialize($deep);
foreach ([0, 1, 2, 3, 4] as $depth) {
    echo "depth $depth\n";
    $result = unserialize($deepData, ['max_depth' => $depth]);
    echo $result === false ? "false\n" : serialize($result) . "\n";
}

spl_autoload_register(function ($class) {
    echo "autoload($class)\n";
    if ($class === 'LazyClass') {
        eval('class LazyClass { public $x; }');
    }
});
echo serialize(unserialize('O:9:"LazyClass":1:{s:1:"x";i:5;}')), "\n";

ini_set('unserialize_callback_func', 'make_class');
function make_class($class) {
    echo "callback($class)\n";
    if ($class === 'MadeByCallback') {
        eval('class MadeByCallback { public $x; }');
    }
}
echo serialize(unserialize('O:14:"MadeByCallback":1:{s:1:"x";i:6;}')), "\n";
echo serialize(unserialize('O:7:"Missing":1:{s:1:"x";i:7;}')), "\n";
echo ini_get('unserialize_callback_func'), "\n";

$incomplete = unserialize($dynamic, ['allowed_classes' => false]);
echo get_class($incomplete), "\n";
echo serialize($incomplete), "\n";
echo ($incomplete instanceof __PHP_Incomplete_Class) ? 'incomplete' : 'other', "\n";

$refs = unserialize('a:2:{i:0;a:1:{i:0;i:1;}i:1;R:2;}');
$refs[1][0] = 99;
echo serialize($refs), "\n";

$strong = unserialize('O:8:"stdClass":2:{s:1:"a";i:1;s:1:"b";R:2;}');
$strong->b = 7;
echo serialize($strong), "\n";

$same = unserialize('O:8:"stdClass":2:{s:1:"a";O:8:"stdClass":0:{}s:1:"b";r:2;}');
echo ($same->a === $same->b) ? 'same' : 'different', "\n";

$badRef = 'a:2:{i:0;O:8:"stdClass":0:{}i:1;R:3;}';
var_dump(unserialize($badRef));
