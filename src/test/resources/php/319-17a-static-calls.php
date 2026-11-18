<?php
// Bytecode 17a: static calls through parent::, self:: and static:: forwarding, an instance
// method reached through self::/$this, dynamic method names, __callStatic, and __call
// reached by a forwarding self:: call that has a $this.

class ScBase {
    public static function who() { return 'base:' . static::class; }
    public static function selfWho() { return 'self:' . self::who(); }
    public static function staticWho() { return 'static:' . static::who(); }
    public function label() { return 'label:' . get_class($this); }
    public function ownLabel() { return 'own:' . self::label(); }
}

class ScChild extends ScBase {
    public static function who() { return 'child:' . static::class; }
    public static function parentWho() { return 'parent:' . parent::who(); }
    public function label() { return 'child-label:' . get_class($this); }
}

echo ScBase::selfWho(), "\n";
echo ScChild::selfWho(), "\n";
echo ScBase::staticWho(), "\n";
echo ScChild::staticWho(), "\n";
echo ScChild::parentWho(), "\n";

$b = new ScBase();
$c = new ScChild();
echo $b->ownLabel(), "|", $c->ownLabel(), "\n";

$name = 'who';
echo ScChild::$name(), "\n";

class ScMagic {
    public static function __callStatic($name, $args) {
        return 'callStatic:' . static::class . ":$name(" . implode(',', $args) . ')';
    }
    public function __call($name, $args) {
        return 'call:' . static::class . ":$name(" . implode(',', $args) . ')';
    }
    public function run() {
        return self::missing(1, 2);
    }
}

echo ScMagic::missing(3, 4), "\n";
$m = new ScMagic();
echo $m->run(), "\n";
echo $m->other('x'), "\n";

class ScRef {
    public static function bump(&$n) { $n++; return $n; }
}

$n = 4;
echo ScRef::bump($n), ':', $n, "\n";
