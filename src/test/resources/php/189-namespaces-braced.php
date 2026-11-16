<?php
namespace X\Y {
    const C = 'xy';
    function hello() {
        return __NAMESPACE__ . '\\hello';
    }
    class Greeter {
        public static function greet() {
            return __CLASS__;
        }
    }
}

namespace {
    use X\Y\Greeter;
    use X\Y\Greeter as G;
    use function X\Y\hello;
    use const X\Y\C;

    echo __NAMESPACE__ === '' ? 'global' : __NAMESPACE__, "\n";
    echo hello(), '|', C, "\n";
    echo Greeter::greet(), '|', G::greet(), '|', \X\Y\Greeter::greet(), "\n";
}
