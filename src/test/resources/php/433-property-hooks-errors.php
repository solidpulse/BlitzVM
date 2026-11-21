<?php

function attempt(string $label, callable $fn): void
{
    try {
        $fn();
        echo $label, ": no error\n";
    } catch (Error $e) {
        $message = $e->getMessage();
        $calledIn = strpos($message, ", called in ");
        if ($calledIn !== false) {
            $message = substr($message, 0, $calledIn);
        }
        echo $label, ": ", get_class($e), ": ", $message, "\n";
    }
}

class Virtuals
{
    public string $getOnly {
        get => "value";
    }

    public string $setOnly {
        set {
            $this->stored = $value;
        }
    }

    public string $stored = "";

    public array $items = [];

    public array $array {
        get => $this->items;
    }

    public array $byRefItems = [1];

    public array $byRef {
        &get {
            return $this->byRefItems;
        }
    }
}

$v = new Virtuals();

attempt("read set-only", function () use ($v) {
    echo $v->setOnly;
});
attempt("write get-only", function () use ($v) {
    $v->getOnly = "x";
});
attempt("unset hooked", function () use ($v) {
    unset($v->getOnly);
});
attempt("isset set-only", function () use ($v) {
    var_dump(isset($v->setOnly));
});
attempt("indirect virtual", function () use ($v) {
    $v->array[] = 2;
});
attempt("reference virtual", function () use ($v) {
    $r = &$v->getOnly;
});
$v->byRef[] = 2;
$v->byRef[] = 3;
print_r($v->byRefItems);

class Typed
{
    public string $name {
        get => $this->name;
        set {
            $this->name = $value;
        }
    }

    public int $count {
        get => $this->count;
        set {
            $this->count = $value;
        }
    }
}

$t = new Typed();
attempt("type set", function () use ($t) {
    $t->name = null;
});
attempt("type get", function () use ($t) {
    $t->count = "not a number";
});
attempt("uninitialized get", function () use ($t) {
    echo $t->name;
});
attempt("reference backed", function () use ($t) {
    $r = &$t->name;
});
$t->name = "ok";
echo $t->name, "\n";

class PrivateSet
{
    public private(set) int $value = 1;
    public protected(set) string $text = "a";

    public int $counter = 0;

    public function bump(): void
    {
        $this->value++;
        $this->text .= "!";
    }

    public function __set(string $name, mixed $value): void
    {
        echo "__set:", $name, "\n";
    }
}

$ps = new PrivateSet();
$ps->bump();
echo $ps->value, " ", $ps->text, "\n";
attempt("private set", function () use ($ps) {
    $ps->value = 2;
});
attempt("protected set", function () use ($ps) {
    $ps->text = "b";
});
attempt("private inc", function () use ($ps) {
    $ps->value++;
});
attempt("private append", function () use ($ps) {
    $ps->text .= "?";
});
attempt("private index", function () use ($ps) {
    $ps->value[0] = 1;
});
attempt("private unset", function () use ($ps) {
    unset($ps->value);
});
attempt("private reference", function () use ($ps) {
    $r = &$ps->value;
});
attempt("protected magic set", function () use ($ps) {
    $ps->text = "c";
});
