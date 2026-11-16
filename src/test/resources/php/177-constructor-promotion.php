<?php
class Point {
    public string $label;
    public function __construct(
        public int $x,
        protected int $y = 2,
        private ?string $tag = null,
        string $prefix = 'P'
    ) {
        $this->label = "$prefix({$this->x},{$this->y}):" . ($this->tag ?? 'none');
    }
    public function summary(): string {
        return "{$this->x},{$this->y}," . ($this->tag ?? 'null') . ",{$this->label}";
    }
}

$p1 = new Point(5);
$p2 = new Point(y: 9, x: 4, tag: 'origin', prefix: 'Q');
echo $p1->summary() . "
";
echo $p2->summary() . "
";

$anon = new class(7, 'anon') {
    public function __construct(public $v, private string $name) {}
    public function info(): string {
        return "{$this->name}:{$this->v}";
    }
};
echo $anon->info() . "
";
