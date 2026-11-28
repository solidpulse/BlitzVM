<?php
function f1($x, $y) { return "f1:$x,$y"; }
function f2($y, $x) { return "f2:$x,$y"; }
function f3($x, $y, $z = "z") { return "f3:$x,$y,$z"; }
function f4($y, $x, $w = "w") { return "f4:$x,$y,$w"; }
function f5($x = "x", $y = "y") { return "f5:$x,$y"; }
function f6($q = "q", $x = "x", $y = "y") { return "f6:$x,$y,$q"; }

$functions = ["f1", "f2", "f3", "f4", "f5", "f6"];
for ($round = 1; $round <= 2; $round++) {
    foreach ($functions as $name) {
        $callable = $name;
        echo $callable(x: "X$round", y: "Y$round"), "\n";
    }
}

$closures = [
    function ($x, $y) { return "c1:$x,$y"; },
    function ($y, $x) { return "c2:$x,$y"; },
    function ($x, $y, $z = "z") { return "c3:$x,$y,$z"; },
    function ($y, $x, $w = "w") { return "c4:$x,$y,$w"; },
    function ($x = "x", $y = "y") { return "c5:$x,$y"; },
    function ($q = "q", $x = "x", $y = "y") { return "c6:$x,$y,$q"; },
];
for ($round = 1; $round <= 2; $round++) {
    foreach ($closures as $closure) {
        echo $closure(x: "X$round", y: "Y$round"), "\n";
    }
}

class M1 { public function go($x, $y) { return "M1:$x,$y"; } }
class M2 { public function go($y, $x) { return "M2:$x,$y"; } }
class M3 { public function go($x, $y, $z = "z") { return "M3:$x,$y,$z"; } }
class M4 { public function go($y, $x, $w = "w") { return "M4:$x,$y,$w"; } }
class M5 { public function go($x = "x", $y = "y") { return "M5:$x,$y"; } }
class M6 { public function go($q = "q", $x = "x", $y = "y") { return "M6:$x,$y,$q"; } }

$objects = [new M1(), new M2(), new M3(), new M4(), new M5(), new M6()];
for ($round = 1; $round <= 2; $round++) {
    foreach ($objects as $object) {
        echo $object->go(x: "X$round", y: "Y$round"), "\n";
    }
}

class N1 { public function __construct(public $x, public $y) {} }
class N2 { public function __construct(public $y, public $x) {} }
class N3 { public function __construct(public $x, public $y, public $z = "z") {} }
class N4 { public function __construct(public $y, public $x, public $w = "w") {} }
class N5 { public function __construct(public $x = "x", public $y = "y") {} }
class N6 { public function __construct(public $q = "q", public $x = "x", public $y = "y") {} }

$classes = [N1::class, N2::class, N3::class, N4::class, N5::class, N6::class];
for ($round = 1; $round <= 2; $round++) {
    foreach ($classes as $class) {
        $object = new $class(x: "X$round", y: "Y$round");
        echo str_replace("N", "n", $class), ":", json_encode((array) $object), "\n";
    }
}

class S1 { public static function make($x, $y) { return "S1:$x,$y"; } }
class S2 { public static function make($y, $x) { return "S2:$x,$y"; } }
class S3 { public static function make($x, $y, $z = "z") { return "S3:$x,$y,$z"; } }

$statics = [S1::class, S2::class, S3::class, S1::class, S2::class, S3::class];
foreach ($statics as $class) {
    echo $class::make(x: "X", y: "Y"), "\n";
}
