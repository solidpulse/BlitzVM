<?php
interface RealIface { public function value(): int; }
class Real implements RealIface { public function value(): int { return 7; } }
class Other {}

class_alias('Real', 'Alias');
class_alias('RealIface', 'AliasIface');

function takeAlias(Alias $x): Alias { return $x; }
function takeAliasIface(AliasIface $x): AliasIface { return $x; }

$real = new Real();
var_dump(takeAlias($real) === $real);
var_dump(takeAliasIface($real) === $real);
echo takeAlias($real)->value(), "\n";

var_dump($real instanceof Alias);
var_dump($real instanceof AliasIface);
var_dump($real instanceof Real);
var_dump($real instanceof RealIface);

$name = 'Alias';
var_dump($real instanceof $name);
$name = 'AliasIface';
var_dump($real instanceof $name);

$other = new Other();
var_dump($other instanceof Alias, $other instanceof AliasIface);

class AliasBox {
    public Alias $item;
    public AliasIface $iface;
}
$box = new AliasBox();
$box->item = $real;
$box->iface = $real;
var_dump($box->item === $real, $box->iface === $real);

try { takeAlias($other); } catch (TypeError $e) { echo preg_replace('/, called in .*$/', '', $e->getMessage()), "\n"; }
try { takeAliasIface($other); } catch (TypeError $e) { echo preg_replace('/, called in .*$/', '', $e->getMessage()), "\n"; }
try { $box->item = $other; } catch (TypeError $e) { echo $e->getMessage(), "\n"; }

class_alias('Alias', 'AliasOfAlias');
function takeAliasOfAlias(AliasOfAlias $x): AliasOfAlias { return $x; }
var_dump(takeAliasOfAlias($real) === $real);
var_dump($real instanceof AliasOfAlias);
