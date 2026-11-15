<?php

interface Greeter {
    public function greet(): string;
}

class PrefixBase {
    protected string $prefix;

    public function __construct(string $prefix) {
        $this->prefix = $prefix;
    }
}

$obj = new class('hello', 'world') extends PrefixBase implements Greeter {
    private string $target;

    public function __construct(string $prefix, string $target) {
        parent::__construct($prefix);
        $this->target = $target;
    }

    public function greet(): string {
        return $this->prefix . ' ' . $this->target;
    }
};

echo $obj->greet(), "\n";
var_dump(
    $obj instanceof PrefixBase,
    $obj instanceof Greeter,
    str_contains(get_class($obj), '@anonymous'),
    is_object($obj)
);

$loopObjs = [];
for ($i = 0; $i < 3; $i++) {
    $loopObjs[] = new class($i) extends PrefixBase {
        public int $n;
        public function __construct(int $n) {
            parent::__construct('item');
            $this->n = $n;
        }
        public function label(): string {
            return $this->prefix . '#' . $this->n;
        }
    };
}

echo $loopObjs[0]->label(), ',', $loopObjs[1]->label(), ',', $loopObjs[2]->label(), "\n";
var_dump(
    get_class($loopObjs[0]) === get_class($loopObjs[1]),
    $loopObjs[0] instanceof $loopObjs[2],
    $loopObjs[0] !== $loopObjs[1]
);

class Factory {
    public function make(int $x): object {
        return new class($x) {
            public int $x;
            public function __construct(int $x) {
                $this->x = $x;
            }
        };
    }
}

$f = new Factory();
$m1 = $f->make(10);
$m2 = $f->make(20);
echo $m1->x, ':', $m2->x, "\n";
var_dump(get_class($m1) === get_class($m2), str_starts_with(get_class($m1), 'class@anonymous'));
