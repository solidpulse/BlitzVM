<?php
// class_exists/interface_exists/enum_exists/trait_exists/defined: names, case, aliases,
// leading backslash, autoloading and user constants.
namespace App\Model {
    interface Contract {}
    trait Helper {}
    enum Status: string { case On = 'on'; }
    class Widget implements Contract { use Helper; const KIND = 'widget'; }
    class Child extends Widget {}
    abstract class Base {}
}

namespace {
    use App\Model\Widget;

    $autoloads = 0;
    $loader = function ($name) use (&$autoloads) {
        $autoloads++;
        if ($name === 'LazyThing') {
            eval('class LazyThing {}');
        }
    };
    spl_autoload_register($loader);

    function show($value) { echo $value ? 'true' : 'false'; echo "\n"; }

    // Existing classes, exact and different case, leading backslash.
    show(class_exists('App\Model\Widget'));
    show(class_exists('app\model\widget'));
    show(class_exists('\App\Model\Widget'));
    show(class_exists('App\Model\Child'));
    show(class_exists('ArrayObject'));
    show(class_exists('arrayobject'));
    show(class_exists('ARRAYOBJECT'));
    show(class_exists('\ArrayObject'));
    show(class_exists('stdClass'));
    show(class_exists('NoSuchClass'));
    show(class_exists('NoSuchClass', false));

    // Autoload on and off.
    show(class_exists('LazyThing'));
    echo "autoloads=", $autoloads, "\n";
    spl_autoload_unregister($loader);
    show(class_exists('AnotherLazy'));

    // Interfaces, traits and enums are not classes.
    show(class_exists('App\Model\Contract'));
    show(interface_exists('App\Model\Contract'));
    show(interface_exists('app\model\contract'));
    show(class_exists('App\Model\Helper'));
    show(trait_exists('App\Model\Helper'));
    show(trait_exists('App\Model\Widget'));
    show(enum_exists('App\Model\Status'));
    show(enum_exists('App\Model\Widget'));
    show(interface_exists('NoSuchInterface'));
    show(trait_exists('NoSuchTrait'));
    show(enum_exists('NoSuchEnum'));

    // Aliases.
    class_alias('App\Model\Widget', 'WidgetAlias');
    show(class_exists('WidgetAlias'));
    show(class_exists('widgetalias'));
    show(class_exists('\WidgetAlias'));
    show(class_exists('App\Model\Widget'));

    // Class constants through defined(), including a missing class and autoloading.
    show(defined('App\Model\Widget::KIND'));
    show(defined('\App\Model\Widget::KIND'));
    show(defined('App\Model\Widget::MISSING'));
    show(defined('App\Model\Child::KIND'));
    show(defined('NoSuchClass::KIND'));

    // Global constants: built-in, user, case sensitivity, leading backslash.
    define('MY_CONSTANT', 42);
    define('App\MY_NAMESPACED', 'yes');
    show(defined('MY_CONSTANT'));
    show(defined('\MY_CONSTANT'));
    show(defined('my_constant'));
    show(defined('App\MY_NAMESPACED'));
    show(defined('PHP_VERSION'));
    show(defined('\PHP_VERSION'));
    show(defined('php_version'));
    show(defined('PHP_INT_MAX'));
    show(defined('true'));
    show(defined('FALSE'));
    show(defined('null'));
    show(defined('NoSuchConstant'));
    show(defined(''));
    // A miss must not be cached: define() can add the name later.
    show(defined('LATE_CONSTANT'));
    define('LATE_CONSTANT', 'late');
    show(defined('LATE_CONSTANT'));
    show(defined('LATE_CONSTANT'));
}
