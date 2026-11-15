<?php

#[AllowDynamicProperties]
class Maker {
    public const KIND = 'maker';
    public static int $count = 0;
    public int $val;
    public ?Maker $child = null;

    public function __construct(int $v = 1) {
        $this->val = $v;
        static::$count++;
    }

    public static function makeStatic(): static {
        return new static(7);
    }

    public static function makeSelf(): self {
        return new self(3);
    }

    public function times(int $k): int {
        return $this->val * $k;
    }

    public static function ping(string $s): string {
        return static::class . ':' . $s;
    }
}

class SubMaker extends Maker {
    public const KIND = 'submaker';
    public static int $count = 10;

    public static function makeParent(): Maker {
        return new parent(9);
    }
}

$cls = 'SubMaker';
$o = new $cls(4);
echo get_class($o), ':', $o->val, "\n";
$s1 = SubMaker::makeStatic();
$s2 = SubMaker::makeSelf();
$s3 = SubMaker::makeParent();
echo get_class($s1), ',', get_class($s2), ',', get_class($s3), "\n";

$prop = 'val';
echo $o->$prop, "\n";
$o->{'dyn_' . 'x'} = 'ok';
echo $o->{'dyn_' . 'x'}, "\n";

$m = 'times';
echo $o->$m(3), '|', $o->{'tim' . 'es'}(5), "\n";

echo $cls::KIND, '|', $cls::$count, '|', $cls::ping('hi'), "\n";
$sm = 'ping';
echo $cls::$sm('var'), '|', $cls::{'pi' . 'ng'}('expr'), "\n";

$hits = 0;
function boom(): int {
    global $hits;
    $hits++;
    return 9;
}

$none = null;
var_dump($none?->val, $none?->times(boom()), $none?->child?->val, $none?->child->val, $hits);

$o->child = new Maker(20);
var_dump($o?->val, $o?->child?->times(boom()), $o?->child?->child?->val, $hits);
