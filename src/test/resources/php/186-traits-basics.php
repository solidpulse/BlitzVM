<?php
class BaseCls {
    const TAG = 'Base';
    public static function who() {
        return 'Base';
    }
}

trait CounterTrait {
    const TRAIT_CONST = 'tr';
    private $priv = 't_priv';
    public $pub = 10;
    public static $hits = 0;

    abstract public function label();

    public static function bump() {
        static::$hits++;
        return self::TAG . ':' . static::TAG . ':' . static::$hits;
    }

    public function describe() {
        $fn = function () {
            return __TRAIT__ . '/' . __CLASS__ . '/' . self::TAG;
        };
        return implode('|', [
            __TRAIT__,
            __CLASS__,
            __METHOD__,
            self::TAG,
            static::TAG,
            parent::who(),
            get_class(),
            get_parent_class(),
            $this->priv,
            $this->ownPriv,
            $this->label(),
            $fn(),
        ]);
    }
}

class UserA extends BaseCls {
    use CounterTrait;
    const TAG = 'UserA';
    private $ownPriv = 'a_priv';
    public static function who() {
        return 'UserA';
    }
    public function label() {
        return 'lblA';
    }
}

class ChildA extends UserA {
    const TAG = 'ChildA';
}

class UserB extends BaseCls {
    use CounterTrait;
    const TAG = 'UserB';
    private $ownPriv = 'b_priv';
    public function label() {
        return 'lblB';
    }
}

$ca = new ChildA();
echo $ca->describe(), "\n";
echo (new UserB())->describe(), "\n";
echo UserA::bump(), '|', ChildA::bump(), '|', UserB::bump(), "\n";
echo UserA::$hits, '|', UserB::$hits, '|', UserA::TRAIT_CONST, "\n";
