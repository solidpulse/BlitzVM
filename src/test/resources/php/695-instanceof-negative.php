<?php
namespace App {
    interface Shape {}
    interface Named {}
    abstract class Base implements Shape {}
    class Circle extends Base implements Named {}
    class Square extends Base {}
    final class Plain {}
    enum Suit: string implements Named { case Hearts = 'h'; }

    function check(string $label, $v): void {
        echo str_pad($label, 10), ' ',
            var_export($v instanceof Shape, true), ' ', var_export($v instanceof Named, true), ' ',
            var_export($v instanceof Base, true), ' ', var_export($v instanceof Circle, true), ' ',
            var_export($v instanceof Square, true), ' ', var_export($v instanceof \Countable, true), ' ',
            var_export($v instanceof \Stringable, true), ' ', var_export($v instanceof \Traversable, true), ' ',
            var_export($v instanceof \Closure, true), ' ', var_export($v instanceof \UnitEnum, true), ' ',
            var_export($v instanceof NotDeclared, true), ' ', var_export($v instanceof \Later\Thing, true), "\n";
    }

    function aliasCheck($v): string {
        return var_export($v instanceof \Aliased\Round, true) . ' ' . var_export($v instanceof \Aliased\Figure, true);
    }

    class Text { public function __toString(): string { return 't'; } }
}

namespace {
    spl_autoload_register(function ($c) { echo "autoload($c)\n"; });
    $values = ['circle' => new App\Circle, 'square' => new App\Square, 'plain' => new App\Plain,
        'enum' => App\Suit::Hearts, 'closure' => fn() => 1, 'array' => [1], 'string' => 'App\Circle',
        'null' => null, 'int' => 3, 'text' => new App\Text, 'arrayobj' => new ArrayObject([]),
        'generator' => (function () { yield 1; })()];
    // Loop many times so the compiled code answers too, not just the first run.
    for ($round = 0; $round < 3; $round++) {
        foreach ($values as $label => $v) {
            if ($round === 2) { App\check($label, $v); }
            else { ob_start(); App\check($label, $v); ob_end_clean(); }
        }
        for ($i = 0; $i < 2000; $i++) { foreach ($values as $v) { ob_start(); App\check('', $v); ob_end_clean(); } }
    }
    // An alias created after the sites have answered false for its name.
    echo App\aliasCheck(new App\Circle), "\n";
    for ($i = 0; $i < 3000; $i++) { App\aliasCheck(new App\Circle); App\aliasCheck(new App\Plain); }
    class_alias('App\Circle', 'Aliased\Round');
    class_alias('App\Shape', 'Aliased\Figure');
    echo App\aliasCheck(new App\Circle), "\n";
    echo App\aliasCheck(new App\Square), "\n";
    echo App\aliasCheck(new App\Plain), "\n";
    echo App\aliasCheck('x'), "\n";
    // A class declared after the sites ran.
    eval('namespace Later; class Thing {} class Sub extends Thing {}');
    App\check('later', new Later\Sub);
    App\check('later2', new Later\Thing);
}
