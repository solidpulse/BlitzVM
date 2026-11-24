<?php
// Property hooks: get and set hooks, a by-reference get hook, and indirect modification
// ($obj->prop[] = ...) from outside and from inside a hook, where the running hook's guard
// lets the access through to the backing store.
class Hooked
{
    public array $items = [] {
        get {
            return $this->items;
        }
    }

    public array $byref = [] {
        &get {
            return $this->byref;
        }
    }

    public array $backing = [];

    public array $virtual {
        get {
            return $this->backing;
        }
        set {
            $this->backing = $value;
        }
    }

    public function appendOutside(): void
    {
        $this->items[] = 2;
    }

    public function appendInsideGet(): void
    {
        $this->items[] = 1;
    }

    public function appendByRef(): void
    {
        $this->byref[] = 3;
    }

    public function appendVirtualOutside(): void
    {
        $this->virtual[] = 4;
    }

    public function appendVirtualInsideSet(array $value): void
    {
        $this->virtual = $value;
        $this->virtual[] = 5;
    }

    public function itemCount(): int
    {
        return count($this->items);
    }
}

$h = new Hooked();
try {
    $h->appendOutside();
    echo "outside ok\n";
} catch (Throwable $e) {
    echo get_class($e), ": ", $e->getMessage(), "\n";
}
try {
    $h->appendInsideGet();
    echo "inside get ok\n";
} catch (Throwable $e) {
    echo get_class($e), ": ", $e->getMessage(), "\n";
}
try {
    $h->appendByRef();
    echo "byref ok\n";
} catch (Throwable $e) {
    echo get_class($e), ": ", $e->getMessage(), "\n";
}
try {
    $h->appendVirtualOutside();
    echo "virtual outside ok\n";
} catch (Throwable $e) {
    echo get_class($e), ": ", $e->getMessage(), "\n";
}
try {
    $h->appendVirtualInsideSet([9]);
    echo "virtual inside set ok\n";
} catch (Throwable $e) {
    echo get_class($e), ": ", $e->getMessage(), "\n";
}
var_dump($h->items, $h->byref, $h->backing);
echo $h->itemCount(), "\n";

class HookedChild extends Hooked
{
    public function setItems(array $value): void
    {
        $this->items = $value;
    }
}

$c = new HookedChild();
$c->setItems([7, 8]);
var_dump($c->items);
