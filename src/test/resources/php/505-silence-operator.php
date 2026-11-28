<?php
// @ lowers error_reporting to the fatal levels while the operand runs, and restores it
// after, for every expression form php allows it on.
class SilenceForms
{
    public array $items = [];

    public function method(): int
    {
        return report_level('method');
    }

    public static function staticMethod(): int
    {
        return report_level('static');
    }
}

function report_level(string $where): int
{
    echo "  $where er=", error_reporting(), "\n";
    return 1;
}

function falsy(): bool
{
    echo "  falsy er=", error_reporting(), "\n";
    return false;
}

$object = new SilenceForms();
$closure = function (): int {
    return report_level('closure');
};
$arrow = fn(): int => report_level('arrow');

echo "start er=", error_reporting(), "\n";

$u = @$undefined;
echo "undefined after er=", error_reporting(), " value=", var_export($u, true), "\n";

$i = @$object->items['missing'];
echo "array after er=", error_reporting(), " value=", var_export($i, true), "\n";

$p = @$object->missing;
echo "property after er=", error_reporting(), " value=", var_export($p, true), "\n";

$f = @report_level('function');
echo "call after er=", error_reporting(), " value=$f\n";

$m = @$object->method();
echo "method after er=", error_reporting(), " value=$m\n";

$s = @SilenceForms::staticMethod();
echo "static after er=", error_reporting(), " value=$s\n";

$c = @$closure();
echo "closure after er=", error_reporting(), " value=$c\n";

$a = @$arrow();
echo "arrow after er=", error_reporting(), " value=$a\n";

$b = @strlen('abc');
echo "builtin after er=", error_reporting(), " value=$b\n";

$h = @file_get_contents(__DIR__ . '/inc/no-such-silence-file');
echo "file after er=", error_reporting(), " value=", var_export($h, true), "\n";

$coalesce = @$object->items[1] ?? 2;
echo "coalesce after er=", error_reporting(), " value=$coalesce\n";

if (@!falsy()) {
    echo "if body er=", error_reporting(), "\n";
}

$included = @include __DIR__ . '/inc/silenced-level.php';
echo "include after er=", error_reporting(), " value=", var_export($included, true), "\n";

$required = @require __DIR__ . '/inc/silenced-level.php';
echo "require after er=", error_reporting(), " value=", var_export($required, true), "\n";
