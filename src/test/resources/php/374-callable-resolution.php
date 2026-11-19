<?php
namespace App {
    const NAMESPACED_FLAG = 'ns';

    class Counter {
        const STEP = 2;
        private $value;
        public function __construct($value) { $this->value = $value; }
        public function add($n) { return $this->value + $n; }
        private function secret($n) { return $this->value * $n; }
        public function reveal($n) { return call_user_func([$this, 'secret'], $n); }
        public static function scale($n) { return $n * 3; }
    }

    class Doubler {
        public function add($n) { return $n * 2; }
    }

    trait Helper {
        public function helperName() { return 'helper of ' . self::class; }
    }

    class WithTrait {
        use Helper;
    }

    class Magic {
        public function __call($name, $args) { return 'call:' . $name . ':' . implode(',', $args); }
        public static function __callStatic($name, $args) { return 'static:' . $name . ':' . implode(',', $args); }
    }
}

namespace {
    use App\Counter;
    use App\Doubler;
    use App\WithTrait;
    use App\Magic;

    function localDouble($n) { return $n * 2; }

    echo call_user_func('LOCALDOUBLE', 4), "\n";
    echo call_user_func('\localDouble', 5), "\n";
    echo call_user_func('strlen', 'hello'), "\n";
    echo call_user_func('\strtoupper', 'hello'), "\n";

    var_dump(is_callable('lateDeclared'));
    if (true) {
        function lateDeclared($n) { return 'late:' . $n; }
    }
    var_dump(is_callable('lateDeclared'));
    echo call_user_func('lateDeclared', 6), "\n";

    echo call_user_func('App\Counter::scale', 7), "\n";
    echo call_user_func('\App\Counter::scale', 8), "\n";

    $counter = new Counter(10);
    echo call_user_func([$counter, 'add'], 5), "\n";
    echo call_user_func([$counter, 'scale'], 9), "\n";
    echo call_user_func([$counter, 'add'], 7), "\n";
    echo $counter->reveal(3), "\n";

    // Outside the class php rejects the private method with a TypeError and this runtime with
    // an Error, so only the fact that the call is rejected is printed.
    foreach ([['App\Counter', 'secret'], 'App\Counter::secret'] as $private) {
        try {
            call_user_func($private);
            echo "called\n";
        } catch (Throwable $e) {
            echo "rejected\n";
        }
    }

    $callback = [$counter, 'add'];
    echo call_user_func($callback, 1), "\n";
    $callback[0] = new Doubler();
    echo call_user_func($callback, 3), "\n";

    echo call_user_func([new WithTrait(), 'helperName']), "\n";

    $magic = new Magic();
    echo call_user_func([$magic, 'dynamic'], 1, 2), "\n";
    echo call_user_func(['App\Magic', 'dynamicStatic'], 3, 4), "\n";

    define('APP_FLAG', 'on');
    var_dump(defined('APP_FLAG'));
    var_dump(defined('\APP_FLAG'));
    var_dump(defined('app_flag'));
    var_dump(defined('PHP_VERSION'));
    var_dump(defined('php_version'));
    var_dump(defined('TRUE'));
    var_dump(defined('True'));
    var_dump(defined('false'));
    var_dump(defined('App\NAMESPACED_FLAG'));
    var_dump(defined('\App\NAMESPACED_FLAG'));
    var_dump(defined('App\Counter::STEP'));
    var_dump(defined('\App\Counter::STEP'));
    var_dump(defined('App\Counter::MISSING'));
}
