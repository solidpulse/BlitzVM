<?php
function &globalCounter() {
    static $cnt = 10;
    return $cnt;
}

$v1 = globalCounter();
$v1 = 999;
echo "by_val=" . globalCounter() . "
";

$r1 = &globalCounter();
$r1 = 55;
echo "by_ref=" . globalCounter() . "
";

class Registry {
    public $entries = [];
    public function &entry($key) {
        if (!isset($this->entries[$key])) {
            $this->entries[$key] = ['count' => 0];
        }
        return $this->entries[$key];
    }
    public static $shared = [1, 2];
    public static function &getShared() {
        return self::$shared;
    }
}

$reg = new Registry();
$e = &$reg->entry('home');
$e['count'] = 7;
echo "reg=" . $reg->entries['home']['count'] . "
";

$sh = &Registry::getShared();
$sh[] = 3;
echo "static=" . implode(',', Registry::$shared) . "
";

$box = [100];
$fnRef = function &() use (&$box) {
    return $box[0];
};
$br = &$fnRef();
$br = 250;
echo "closure=" . $box[0] . "
";
