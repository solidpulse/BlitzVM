<?php
function run_eval(): void {
    $base = 5;
    eval('$doubled = $base * 2; function evaled_func(): int { return 3; }');
    echo "local:$doubled:", evaled_func(), "\n";
}
run_eval();
eval('final class EvaledBox { public int $n = 4; public function get(): int { return $this->n + evaled_func(); } }');
$b = new EvaledBox();
echo "class:", $b->get(), "\n";
