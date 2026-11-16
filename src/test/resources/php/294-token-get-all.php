<?php
$cases = [];
$cases[] = <<<'SRC'
<?php echo 1; ?>
html
<?= $x ?>
SRC;
$cases[] = <<<'SRC'
<?php
$a = 'it\'s';
$b = "a\"b";
$c = "hello $name and {$obj->prop} and ${var} and $arr[0] and $arr[key] and $arr[$i]";
$d = `echo $x`;
SRC;
$cases[] = <<<'SRC'
<?php
$a = <<<EOT
hello $x
EOT;
$b = <<<'NOW'
raw $x
NOW;
$c = <<<"EOT"
hi
  EOT;
SRC;
$cases[] = <<<'SRC'
<?php // line comment
# hash comment
/* block */ /** doc */ /**#@+ template */ #[Attr] class A {}
SRC;
$cases[] = <<<'SRC'
<?php
$a & $b;
$c = &$d;
function f(&$p, &...$rest) {}
$e = $x | $y;
$f = $x || $y;
$g = $x && $y;
$h &= $i;
SRC;
$cases[] = <<<'SRC'
<?php
Foo\Bar::baz();
\Foo\Bar::x();
namespace\Foo::y();
use A\B as C;
(list) $x;
(int)$a; (integer)$a; (bool)$a; (boolean)$a; (float)$a; (double)$a;
(real)$a; (string)$a; (array)$a; (object)$a; (unset)$a;
SRC;
$cases[] = <<<'SRC'
<?php
$a = $b ?-> c;
$d = $e <=> $f;
$g ??= $h;
$i **= 2;
$j = ...$k;
$l = $m === $n;
$o = $p !== $q;
$r = $s << 2;
$t = $u >> 2;
SRC;
$cases[] = <<<'SRC'
<?php
class A {
    public private(set) int $x;
    public protected(set) $y;
    public public(set) $z;
}
enum E: string { case A = "a"; }
SRC;
$cases[] = <<<'SRC'
<?php
yield from $x;
YIELD FROM $y;
__CLASS__ __DIR__ __FILE__ __FUNCTION__ __LINE__ __METHOD__ __NAMESPACE__ __TRAIT__ __PROPERTY__;
$n = 0xFF + 0b101 + 017 + 1_000 + 1.5e3 + .5 + 5.;
SRC;
$cases[] = <<<'SRC'
<?php
__halt_compiler(); raw data here
SRC;
$cases[] = <<<'SRC'
<?phpecho 1;
<?xml version="1.0"?>
<?PHP echo 2; ?>
SRC;
foreach ($cases as $src) {
    echo json_encode(token_get_all($src), JSON_UNESCAPED_SLASHES), "\n";
}
echo token_name(T_LNUMBER), " ", token_name(T_STRING), " ", token_name(T_OPEN_TAG), "\n";
echo token_name(43), " ", token_name(9999), " ", token_name(TOKEN_PARSE), "\n";
echo T_LNUMBER, " ", T_VARIABLE, " ", T_NAME_QUALIFIED, " ", T_NULLSAFE_OBJECT_OPERATOR, "\n";
