<?php
function done(): string { return 'done'; }
final class H {
    public string $s = 'h';
    public function get(): string { return $this->s . done(); }
}
$h = new H();
register_shutdown_function(function () use ($h): void { echo "shutdown:", $h->get(), "\n"; });
function report(string $tag): void { echo "report:$tag\n"; }
register_shutdown_function('report', 'two');
echo "body\n";
