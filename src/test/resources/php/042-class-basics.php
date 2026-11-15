<?php

#[Attribute]
class Tag {
}

#[AllowDynamicProperties]
#[Tag('demo')]
class Box {
    #[Tag]
    public const VERSION = 1;

    #[Tag]
    public int $value;
    public array $items = [];
    protected string $prot = 'secret';
    private string $priv = 'hidden';

    public function __construct(#[Tag] int $value = 10) {
        $this->value = $value;
    }

    public function addItem(string $group, mixed $item): void {
        $this->items[$group][] = $item;
    }

    public function dumpInner(): array {
        return get_object_vars($this);
    }

    public function getPrivate(): string {
        return $this->priv;
    }
}

class NoCtor {
    public $x = 42;
}

$a = new Box(5);
$b = new box(5);
$c = $a;
$d = new NoCtor;

echo "values: ", $a->value, ",", $d->x, "\n";
$c->value = 77;
echo "handle: ", $a->value, "\n";

$a2 = new Box(77);
$other = new NoCtor;
var_dump($a === $c, $a === $a2, $a == $a2, $a == $b, $a == $other);

$a->dyn = 'hello';
echo "dyn: ", $a->dyn, "\n";
var_dump(isset($a->dyn), isset($a->missing));
echo "coalesce: ", ($a->missing ?? 'fallback'), "\n";

$a->counter = 1;
$a->counter += 4;
echo "inc: ", $a->counter++, ",", ++$a->counter, ",", $a->counter--, ",", $a->counter, "\n";

unset($a->dyn);
var_dump(isset($a->dyn));

$a->addItem('nums', 10);
$a->addItem('nums', 20);
$a->items['nums'][] = 30;
echo "items: ", implode(',', $a->items['nums']), "\n";

var_dump(is_object($a), is_object(123), gettype($a), get_debug_type($a), get_class($a));
var_dump(class_exists('box'), class_exists('Nope'), interface_exists('Box'));
var_dump(method_exists($a, 'ADDITEM'), method_exists('Box', 'nope'));
var_dump(property_exists($a, 'value'), property_exists($a, 'priv'), property_exists('Box', 'items'), property_exists($a, 'nope'));

echo "ids differ: ";
var_dump(spl_object_id($a) !== spl_object_id($b), strlen(spl_object_hash($a)) === 32, spl_object_hash($a) === spl_object_hash($c));

echo "outside vars:\n";
print_r(get_object_vars($a));
echo "inside vars:\n";
print_r($a->dumpInner());
