<?php
// Private and protected property shadowing across a parent and a child: the same name is a
// different storage slot for private, and the same slot for a redeclared protected property.
class ShadowParent
{
    private string $name = 'parent';
    protected int $count = 1;
    public string $shared = 'pshared';

    public function parentName(): string
    {
        return $this->name;
    }

    public function parentCount(): int
    {
        return $this->count;
    }

    public function bump(): void
    {
        $this->count++;
    }
}

class ShadowChild extends ShadowParent
{
    private string $name = 'child';
    protected int $count = 10;
    public string $shared = 'cshared';

    public function childName(): string
    {
        return $this->name;
    }

    public function childCount(): int
    {
        return $this->count;
    }
}

$c = new ShadowChild();
var_dump($c->parentName(), $c->childName());
var_dump($c->parentCount(), $c->childCount());
$c->bump();
$c->bump();
var_dump($c->parentCount(), $c->childCount());
var_dump($c->shared);
$c->shared = 'changed';
var_dump($c->shared);

class ThreeLevels extends ShadowChild
{
    public function nameFromMid(): string
    {
        return 'unused';
    }
}

$t = new ThreeLevels();
var_dump($t->parentName(), $t->childName());
