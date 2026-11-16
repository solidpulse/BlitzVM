<?php
class BaseConst {
    const ROOT = 'root';
    const MAP = [
        'a' => ['x' => 10, 'nil' => null, 'zero' => 0],
        'b' => 'bee',
    ];
}

class SubConst extends BaseConst {
    const MAP = [
        'a' => ['x' => 20, 'nil' => null, 'zero' => 0],
        'sub' => 'yes',
    ];
    const GREET = self::ROOT . '->' . self::MAP['sub'];
    const TABLE = [self::ROOT => 1, self::GREET => 2];

    public $inst = self::GREET;
    public static $stat = LaterDef::VAL;

    public static function check($k, $k2 = 'x') {
        $r = static::MAP[$k] ?? 'missing';
        $is = isset(static::MAP[$k]) ? '1' : '0';
        $em = empty(static::MAP[$k]) ? '1' : '0';
        $isNested = isset(static::MAP[$k][$k2]) ? '1' : '0';
        return "$is/$em/$isNested:" . (is_array($r) ? 'arr' : $r);
    }
}

class LaterDef {
    const VAL = 42;
}

echo BaseConst::MAP['b'], '|', SubConst::GREET, '|', SubConst::TABLE['root'], '|', SubConst::TABLE['root->yes'], "\n";
echo (new SubConst())->inst, '|', SubConst::$stat, "\n";
echo SubConst::check('sub'), "\n";
echo SubConst::check('nope'), "\n";
echo SubConst::check('a', 'x'), "\n";
echo SubConst::check('a', 'nil'), "\n";
echo SubConst::check('a', 'zero'), "\n";
echo SubConst::check('a', 'absent'), "\n";
var_dump(isset(SubConst::MAP['a']['zero']), isset(SubConst::MAP['a']['nil']), empty(SubConst::MAP['a']['zero']));
echo BaseConst::MAP['missing'] ?? 'fallback', "\n";
