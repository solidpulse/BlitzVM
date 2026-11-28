<?php
namespace App;

function one(string $class): void {
    echo "one:$class\n";
    if ($class === 'App\\AutoOne' && !class_exists('App\\AutoOne', false)) {
        eval('namespace App; class AutoOne {}');
    }
    if ($class === 'App\\AutoCall' && !class_exists('App\\AutoCall', false)) {
        eval('namespace App; class AutoCall {}');
    }
}

function two(string $class): void {
    echo "two:$class\n";
}

function three(string $class): void {
    echo "three:$class\n";
}

function zero(string $class): void {
    echo "zero:$class\n";
}

function late(string $class): void {
    echo "late:$class\n";
    spl_autoload_register('App\\four');
}

function four(string $class): void {
    echo "four:$class\n";
    if ($class === 'App\\AutoLate') {
        eval('namespace App; class AutoLate {}');
    }
}

function tail(string $class): void {
    echo "tail:$class\n";
}

function remover(string $class): void {
    echo "remover:$class\n";
    spl_autoload_unregister('App\\skipped');
}

function skipped(string $class): void {
    echo "skipped:$class\n";
}

function last(string $class): void {
    echo "last:$class\n";
}

function selfUnregister(string $class): void {
    echo "self:$class\n";
    spl_autoload_unregister('App\\selfUnregister');
}

function skippedTwo(string $class): void {
    echo "skipped-two:$class\n";
}

function recursive(string $class): void {
    echo "recursive:$class\n";
    if ($class === 'App\\AutoRecursive') {
        var_dump(class_exists('App\\AutoRecursive'));
        eval('namespace App; class AutoRecursive {}');
    }
}

function throwing(string $class): void {
    echo "throw:$class\n";
    throw new \RuntimeException('boom');
}

function afterThrow(string $class): void {
    echo "after-throw:$class\n";
}

function ifaceLoader(string $class): void {
    echo "iface:$class\n";
    if ($class === 'App\\AutoIface') {
        eval('namespace App; interface AutoIface {}');
    }
}

function childLoader(string $class): void {
    echo "child:$class\n";
    if ($class === 'App\\AutoChild') {
        eval('namespace App; class AutoBase {} class AutoChild extends AutoBase {}');
    }
}

class Loader {
    public static function load(string $class): void {
        echo "loader:$class\n";
    }
}

// Order, a hit that stops the walk, and repeated misses.
spl_autoload_register('App\\one');
spl_autoload_register('App\\two');
spl_autoload_register('App\\three');
var_dump(class_exists('App\\AutoOne'));
var_dump(class_exists('App\\AutoOne'));
var_dump(class_exists('App\\AutoNone'));
var_dump(class_exists('App\\AutoNone'));

// A duplicate registration is ignored; prepend puts the loader first.
spl_autoload_register('App\\one');
echo 'count:', count(spl_autoload_functions()), "\n";
spl_autoload_register('App\\zero', true, true);
var_dump(class_exists('App\\AutoZero'));
spl_autoload_unregister('App\\zero');
spl_autoload_unregister('App\\one');
spl_autoload_unregister('App\\two');
spl_autoload_unregister('App\\three');
echo 'count:', count(spl_autoload_functions()), "\n";

// A loader registered while running joins the same walk, at the end.
spl_autoload_register('App\\late');
spl_autoload_register('App\\tail');
var_dump(class_exists('App\\AutoLate'));
echo 'count:', count(spl_autoload_functions()), "\n";
spl_autoload_unregister('App\\late');
spl_autoload_unregister('App\\tail');
spl_autoload_unregister('App\\four');
echo 'count:', count(spl_autoload_functions()), "\n";

// A loader unregistered before its turn is skipped.
spl_autoload_register('App\\remover');
spl_autoload_register('App\\skipped');
spl_autoload_register('App\\last');
var_dump(class_exists('App\\AutoGone'));
spl_autoload_unregister('App\\remover');
spl_autoload_unregister('App\\skipped');
spl_autoload_unregister('App\\last');

// A loader that removes itself shifts the next one into the slot being left behind.
spl_autoload_register('App\\selfUnregister');
spl_autoload_register('App\\skippedTwo');
spl_autoload_register('App\\last');
var_dump(class_exists('App\\AutoSelfGone'));
spl_autoload_unregister('App\\selfUnregister');
spl_autoload_unregister('App\\skippedTwo');
spl_autoload_unregister('App\\last');

// Recursion on the same name does not re-enter the loaders.
spl_autoload_register('App\\recursive');
var_dump(class_exists('App\\AutoRecursive'));
spl_autoload_unregister('App\\recursive');

// A throwing loader propagates and leaves the in-progress guard released.
spl_autoload_register('App\\throwing');
spl_autoload_register('App\\afterThrow');
try {
    class_exists('App\\AutoThrow');
} catch (\RuntimeException $e) {
    echo 'caught:', $e->getMessage(), "\n";
}
spl_autoload_unregister('App\\throwing');
var_dump(class_exists('App\\AutoOther'));
spl_autoload_unregister('App\\afterThrow');
echo 'count:', count(spl_autoload_functions()), "\n";

// spl_autoload_functions() reports the registered callbacks in order.
spl_autoload_register('App\\one');
spl_autoload_register(['App\\Loader', 'load']);
var_dump(spl_autoload_functions());

// spl_autoload_call() runs the loaders even for a class that is already loaded,
// and for a name class_exists() would not autoload.
var_dump(class_exists('App\\bad-name'));
spl_autoload_call('App\\bad-name');
spl_autoload_call('App\\AutoCall');
var_dump(class_exists('App\\AutoCall'));
spl_autoload_call('App\\AutoCall');
// AutoOne is already loaded: the first loader runs, then the walk stops.
spl_autoload_call('App\\AutoOne');
spl_autoload_unregister('App\\one');
spl_autoload_unregister(['App\\Loader', 'load']);

// interface_exists() and is_a() with a string subject use the loaders too.
spl_autoload_register('App\\ifaceLoader');
var_dump(interface_exists('App\\AutoIface'));
spl_autoload_unregister('App\\ifaceLoader');
spl_autoload_register('App\\childLoader');
var_dump(class_exists('App\\AutoChild', false));
var_dump(is_a('App\\AutoChild', 'App\\AutoBase', true));
spl_autoload_unregister('App\\childLoader');
echo 'count:', count(spl_autoload_functions()), "\n";
