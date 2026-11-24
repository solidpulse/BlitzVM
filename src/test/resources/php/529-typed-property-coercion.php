<?php
// Typed properties: weak-mode coercion on assignment, reads through a reference cell,
// . on a string property followed by reads in several forms, and typed writes to a
// property that has never been initialized.
class Typed
{
    public int $i = 0;
    public float $f = 0.0;
    public string $s = 'a';
    public bool $b = false;
    public ?int $nullable = null;
    public array $arr = [];
    public int $late;

    public function __construct()
    {
        $this->late = 7;
    }
}

$t = new Typed();
$t->i = '42';
var_dump($t->i);
$t->i = 3.9;
var_dump($t->i);
$t->f = '1.5';
var_dump($t->f);
$t->f = 4;
var_dump($t->f);
$t->s = 123;
var_dump($t->s);
$t->b = 1;
var_dump($t->b);
$t->nullable = '5';
var_dump($t->nullable);
$t->nullable = null;
var_dump($t->nullable);
$t->arr = [1, 2];
var_dump($t->arr);
var_dump($t->late);

// .= on a declared string property, then reads of the property and of a copy.
$t->s = 'x';
$t->s .= 'y';
var_dump($t->s);
$copy = $t->s;
$copy .= 'z';
var_dump($t->s, $copy);

// A reference to the property still sees the appended value.
$ref = &$t->s;
$t->s .= '!';
var_dump($t->s, $ref);
$ref .= '?';
var_dump($t->s);

// Typed property unset then re-read follows php's uninitialized rules.
unset($t->late);
try {
    var_dump($t->late);
} catch (Throwable $e) {
    echo get_class($e), ": ", $e->getMessage(), "\n";
}
$t->late = 8;
var_dump($t->late);
