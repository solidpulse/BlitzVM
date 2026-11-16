<?php
// settype converts in place and returns true.
$cases = ["12", " 12", "12.9", "abc", "1e3", "", true, false, null, 3.9];
foreach ($cases as $v) {
    $x = $v;
    var_dump(settype($x, "integer"));
    var_dump($x);
}
foreach (["12", "12.5", "abc", "1e3", "", true, false, null, 3.9] as $v) {
    $x = $v;
    settype($x, "string");
    var_dump($x);
}
foreach (["12", "abc", "1e3", "", true, false, null, 3.9] as $v) {
    $x = $v;
    settype($x, "float");
    var_dump($x);
}
foreach (["", "0", "0.0", "1", "abc", 0, 1, 0.0, null] as $v) {
    $x = $v;
    settype($x, "bool");
    var_dump($x);
}
$x = 5;
settype($x, "array");
var_export($x);
echo "\n";
$o = new stdClass();
$o->a = 1;
$o->b = "two";
$x = $o;
settype($x, "array");
var_export($x);
echo "\n";
$x = "scalar";
settype($x, "object");
var_export($x);
echo "\n";
$x = 42;
settype($x, "null");
var_dump($x);
try {
    settype($x, "bogus");
} catch (Throwable $e) {
    echo get_class($e), ": ", $e->getMessage(), "\n";
}

// get_class_vars: defaults, declaration order (own class first), visibility from the caller.
class VarsBase {
    public $pub = 1;
    protected $prot = 2;
    private $priv = 3;
    public static $stat = 4;
    public $noDefault;
    public $list = [1, 2];
}
class VarsChild extends VarsBase {
    public $child = 9;
    private $childPriv = 8;
    public function inside() {
        VarsChild::$stat = 99;
        return get_class_vars("VarsChild");
    }
}
var_export(get_class_vars("VarsBase"));
echo "\n";
var_export(get_class_vars("VarsChild"));
echo "\n";
var_export((new VarsChild())->inside());
echo "\n";
echo VarsChild::$stat, "\n";
try {
    get_class_vars("MissingClass");
} catch (Throwable $e) {
    echo get_class($e), ": ", $e->getMessage(), "\n";
}
