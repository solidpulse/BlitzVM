<?php
trait TBase {
    const BASE_C = 'tb';
    public $tp = 'from_tbase';
    public function chain() {
        return 'TBase';
    }
    public function shared() {
        return 'TBase::shared';
    }
}

trait TMiddle {
    use TBase;
    const MID_C = self::BASE_C . '_mid';
    public function chain() {
        return 'TMiddle';
    }
    public function fromMiddle() {
        return __METHOD__;
    }
}

trait TOther {
    public function shared() {
        return 'TOther::shared';
    }
    public function visTest() {
        return 'vis';
    }
    abstract public function parentMustImplement();
}

class ParentCls {
    public function chain() {
        return 'Parent';
    }
    public function ownWins() {
        return 'Parent::ownWins';
    }
    public function parentMustImplement() {
        return 'Parent::concrete';
    }
}

class Composed extends ParentCls {
    use TMiddle, TOther {
        TMiddle::shared insteadof TOther;
        TOther::shared as otherShared;
        visTest as protected protVis;
    }

    public function ownWins() {
        return 'Composed::ownWins';
    }

    public function callProt() {
        return $this->protVis();
    }
}

$o = new Composed();
echo $o->chain(), '|', $o->shared(), '|', $o->otherShared(), '|', $o->callProt(), "\n";
echo $o->ownWins(), '|', $o->parentMustImplement(), '|', $o->fromMiddle(), "\n";
echo $o->tp, '|', Composed::BASE_C, '|', Composed::MID_C, "\n";
var_dump(class_uses(Composed::class));
var_dump(class_uses(TMiddle::class));
var_dump(trait_exists('TBase'), class_exists('TBase'), interface_exists('TBase'));
