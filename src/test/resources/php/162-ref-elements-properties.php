<?php
$x = ['p' => 1];
$r = &$x['p'];
$r = 5;
echo "p=" . $x['p'] . "
";

$deep = [];
$dr = &$deep['a']['b']['c'];
$dr = 42;
echo "deep=" . $deep['a']['b']['c'] . "
";

$o = new stdClass();
$o->val = 10;
$or = &$o->val;
$or = 77;
echo "oprop=" . $o->val . "
";

$o->arr = ['k' => 3];
$oar = &$o->arr['k'];
$oar = 33;
echo "oarr=" . $o->arr['k'] . "
";

class Store {
    public static $data = ['m' => 2];
}
$sr = &Store::$data['m'];
$sr = 22;
echo "sprop=" . Store::$data['m'] . "
";

class SelfRef {
    public $tag = 'ok';
    public function makeArr() {
        return [&$this];
    }
}
$srObj = new SelfRef();
$list = $srObj->makeArr();
$list[0]->tag = 'updated';
echo "this=" . $srObj->tag . "
";
