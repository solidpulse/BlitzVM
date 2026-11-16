<?php
namespace IncLib;

use stdClass;

const LIB_VER = '1.2';

function greet($who) {
    return 'hello ' . $who;
}

class Box {
    public $v;
    public function __construct($v) {
        $this->v = $v;
    }
    public function typeOf() {
        $o = new stdClass();
        return get_class($this) . ':' . get_class($o);
    }
}
