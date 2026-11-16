<?php
namespace A\B;

const VAL = 'ns_val';
const PHP_INT_MAX = 777;

function f() {
    return __NAMESPACE__ . '|' . __FUNCTION__;
}

function strlen($s) {
    return 'custom:' . \strlen($s);
}

class K {
    public function info() {
        return __CLASS__ . '|' . __METHOD__ . '|' . __NAMESPACE__;
    }
}

echo f(), "\n";
echo namespace\f(), "\n";
echo K::class, "|", namespace\K::class, "\n";
echo (new K())->info(), "\n";
echo strlen('abc'), '|', \strlen('abc'), "\n";
echo VAL, '|', PHP_INT_MAX, '|', \PHP_INT_MAX > 1000 ? 'big' : 'small', '|', PHP_EOL === "\n" ? 'lf' : 'other', "\n";

namespace C;

function g() {
    return __NAMESPACE__ . '|' . \strlen('abcd') . '|' . \A\B\VAL;
}
echo g(), "\n";
echo \A\B\f(), "\n";
echo \A\B\K::class, "\n";
