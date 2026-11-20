<?php
// Typed properties: weak-mode coercion and TypeError on assignment.
function strip($s) {
    return str_replace(__DIR__ . '/', '', $s);
}

set_error_handler(function (int $errno, string $message) {
    echo 'DEPRECATED: ', $message, "\n";
    return true;
});

class Base {}
class Child extends Base {}

class Typed {
    public int $i;
    public float $f = 1.5;
    public string $s = 'start';
    public bool $b = false;
    public array $a = [];
    public Base $o;
    public int|string $u = 1;
    public ?int $n = null;

    public function assign(string $property, $value) {
        try {
            $this->$property = $value;
            $current = $this->$property;
            if (is_object($current)) {
                echo get_class($current), "\n";
            } else {
                var_dump($current);
            }
        } catch (TypeError $e) {
            echo strip($e->getMessage()), "\n";
        }
    }
}

$t = new Typed();
$t->assign('i', '5');
$t->assign('i', 'x');
$t->assign('i', 5.75);
$t->assign('i', true);
$t->assign('i', null);
$t->assign('f', 3);
$t->assign('f', '4.5');
$t->assign('s', 12);
$t->assign('s', 1.5);
$t->assign('b', '0');
$t->assign('b', '1');
$t->assign('b', 4);
$t->assign('a', [1, 2]);
$t->assign('a', 'no');
$t->assign('o', new Child());
$t->assign('o', new stdClass());
$t->assign('u', 9);
$t->assign('u', 'nine');
$t->assign('u', 9.5);
$t->assign('u', []);
$t->assign('n', null);
$t->assign('n', '12');
$t->assign('n', []);
