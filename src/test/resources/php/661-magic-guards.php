<?php
// __get/__set recursion guards, __isset/__unset, and inheritance of the magic methods.
set_error_handler(function ($no, $msg) {
    // Dynamic-property deprecations are a separate behaviour; this fixture is about the guards.
    if (str_contains($msg, 'Creation of dynamic property')) {
        return true;
    }
    echo "warning: $msg\n";
    return true;
});

class Base {
    protected array $data = [];
    public array $log = [];

    public function __get($name) {
        $this->log[] = "get:$name";
        return $this->data[$name] ?? null;
    }

    public function __set($name, $value) {
        $this->log[] = "set:$name";
        $this->data[$name] = $value;
    }

    public function __isset($name) {
        $this->log[] = "isset:$name";
        return isset($this->data[$name]);
    }

    public function __unset($name) {
        $this->log[] = "unset:$name";
        unset($this->data[$name]);
    }
}

class Child extends Base {
    public function __get($name) {
        $this->log[] = "child-get:$name";
        return parent::__get($name);
    }
}

$b = new Base();
$b->a = 1;
var_dump($b->a);
var_dump($b->missing);
var_dump(isset($b->a));
var_dump(isset($b->missing));
unset($b->a);
var_dump(isset($b->a));
echo implode('|', $b->log), "\n";

$c = new Child();
$c->x = 5;
var_dump($c->x);
echo implode('|', $c->log), "\n";

// Recursion guard: reading the property being read again inside __get must not recurse.
class Loop {
    public int $depth = 0;
    public function __get($name) {
        $this->depth++;
        if ($this->depth > 1) {
            return "nested";
        }
        return $this->$name;
    }
}
$l = new Loop();
var_dump($l->missing);
var_dump($l->depth);

// Setting a property from __set must not re-enter __set for the same name.
class SetLoop {
    public array $raw = [];
    public function __set($name, $value) {
        $this->raw[$name] = $value;
        $this->$name = $value + 1;
    }
}
$s = new SetLoop();
$s->v = 1;
var_dump($s->raw['v'] ?? null);
var_dump(isset($s->v));
