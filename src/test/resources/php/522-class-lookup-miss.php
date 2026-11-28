<?php
// Class lookups that miss are remembered; a later declaration of any kind must be seen.
class Base {}
$o = new Base();
foreach (['Late', 'Aliased', 'Auto\\Loaded', 'Cond', '\\Late'] as $n) {
    var_dump(class_exists($n, false), is_a($o, $n), is_subclass_of($o, $n));
}
class Late extends Base {}
var_dump(class_exists('Late', false), class_exists('\\Late', false), is_a(new Late(), 'Base'), is_a(new Late(), '\\Late'));
class_alias('Base', 'Aliased');
var_dump(class_exists('Aliased', false), is_a($o, 'Aliased'), get_class(new Aliased()));
$calls = 0;
spl_autoload_register(function ($c) use (&$calls) {
    $calls++;
    echo "autoload $c\n";
    if ($c === 'Auto\\Loaded') {
        eval('namespace Auto; class Loaded extends \\Base {}');
    }
});
var_dump(class_exists('Auto\\Loaded'), is_a(new Auto\Loaded(), 'Base'), class_exists('Auto\\Loaded'));
var_dump(class_exists('Nope'), class_exists('Nope'), $calls);
if (true) {
    class Cond {}
}
var_dump(class_exists('Cond', false), interface_exists('Cond', false));
enum Suit { case Hearts; }
var_dump(enum_exists('Suit', false));
for ($i = 0; $i < 5000; $i++) {
    class_exists("Missing$i", false);
}
var_dump(class_exists('Late', false), class_exists('Missing42', false));
