<?php
spl_autoload_register(function (string $class): void {
    echo "autoload:$class\n";
    if ($class === 'Widget') {
        eval('class Widget { public int $n = 7; public function get(): int { return $this->n; } }');
    }
});
$w = new Widget();
echo $w->get(), "\n";
var_dump(class_exists('Missing'));
