<?php
// __get, __set, __isset and __unset, including accesses to the same name from inside the
// magic method: php's per-property guard stops the recursion instead of calling it again.
class Magic
{
    private array $data = ['known' => 1];
    public array $log = [];

    public function __get(string $name): mixed
    {
        $this->log[] = "get:$name";
        if ($name === 'same') {
            $inner = $this->same;
            return $inner === null ? 'null-inner' : $inner;
        }
        return $this->data[$name] ?? null;
    }

    public function __set(string $name, mixed $value): void
    {
        $this->log[] = "set:$name";
        if ($name === 'same') {
            $this->same = $value;
            return;
        }
        $this->data[$name] = $value;
    }

    public function __isset(string $name): bool
    {
        $this->log[] = "isset:$name";
        return isset($this->data[$name]);
    }

    public function __unset(string $name): void
    {
        $this->log[] = "unset:$name";
        unset($this->data[$name]);
    }
}

$m = new Magic();
var_dump($m->known, $m->absent);
var_dump($m->same);
$m->same = 5;
var_dump($m->same);
$m->added = 'x';
var_dump($m->added, $m->added ?? 'none');
var_dump(isset($m->added), isset($m->ghost));
unset($m->added);
var_dump(isset($m->added));
echo implode(',', $m->log), "\n";

// A declared property never reaches __get, even when a same-named private property is
// only visible in the parent.
class MagicParent
{
    private array $hidden = ['parent'];

    public function __get(string $name): mixed
    {
        if ($name === 'hidden') {
            return $this->hidden;
        }
        return 'parent-magic';
    }

    public function parentHidden(): array
    {
        return $this->hidden;
    }
}

class MagicChild extends MagicParent
{
    private array $hidden = ['child'];
    public function childHidden(): array
    {
        return $this->hidden;
    }
}

$c = new MagicChild();
var_dump($c->parentHidden(), $c->childHidden());
var_dump($c->other);
