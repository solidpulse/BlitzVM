<?php
namespace Demo\Sub;

class Base {}
class Widget extends Base {
    public static function ping($x) {
        return static::class . ':' . $x;
    }
}

namespace Caller;

$cls1 = 'Demo\Sub\Widget';
$cls2 = '\Demo\Sub\Widget';
$obj = new $cls1();
$obj2 = new $cls2();
echo get_class($obj), '|', get_class($obj2), "\n";

var_dump(
    class_exists('Demo\Sub\Widget'),
    class_exists('\Demo\Sub\Widget'),
    is_a($obj, 'Demo\Sub\Base'),
    is_a($obj, '\Demo\Sub\Base'),
    is_subclass_of('Demo\Sub\Widget', '\Demo\Sub\Base'),
    $obj instanceof $cls1,
    $obj instanceof $cls2
);

$cb1 = 'Demo\Sub\Widget::ping';
$cb2 = '\Demo\Sub\Widget::ping';
echo $cb1('a'), '|', $cb2('b'), '|', call_user_func(['\Demo\Sub\Widget', 'ping'], 'c'), "\n";

$autoloaded = [];
spl_autoload_register(function ($class) use (&$autoloaded) {
    $autoloaded[] = $class;
    if ($class === 'Lazy\Item') {
        include __DIR__ . '/inc/autoload_ns.php';
    }
});

$missing = '\Missing\Nope';
var_dump(class_exists($missing));
$lazy = new \Lazy\Item();
echo $lazy->tag(), "\n";
echo implode(',', $autoloaded), "\n";
var_dump($lazy);
