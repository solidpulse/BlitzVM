<?php
set_error_handler(function ($no, $msg) {
    if ($no === E_DEPRECATED) {
        return true;
    }
    echo "ERR[$no]: $msg\n";
    return true;
});

class MagicSerialize {
    public $a = 1;
    public $b = 2;
    public function __serialize(): array {
        return ['x' => $this->a, 'nested' => [$this->b, $this]];
    }
    public function __unserialize(array $data): void {
        echo 'unserialize keys: ', implode(',', array_keys($data)), "\n";
        $this->a = 42;
    }
}

class MagicSleep {
    public $keep = 1;
    protected $prot = 2;
    private $priv = 3;
    public int $unset;
    public $skip = 4;
    public function __sleep() {
        return ['keep', 'prot', 'priv', 'unset', 'missing'];
    }
    public function __wakeup() {
        echo "wakeup keep={$this->keep}\n";
    }
}

class BadSleep {
    public $a = 1;
    public function __sleep() {
        return 'a';
    }
}

class DuplicateSleep {
    public $a = 1;
    public $b = 2;
    public function __sleep() {
        return ['a', 'a', 'b'];
    }
}

class NonStringSleep {
    public $a = 1;
    public function __sleep() {
        return [1, 'a', null, true, 2.5];
    }
}

class BadSerialize {
    public function __serialize() {
        return 'no';
    }
}

class Legacy implements Serializable {
    public $a = 1;
    public function serialize() {
        return 'payload';
    }
    public function unserialize($data) {
        echo "legacy unserialize($data)\n";
        $this->a = 99;
    }
}

class LegacyNull implements Serializable {
    public function serialize() {
        return null;
    }
    public function unserialize($data) {}
}

class Both implements Serializable {
    public $a = 1;
    public function __serialize(): array {
        echo "both __serialize\n";
        return ['z' => 9];
    }
    public function __unserialize(array $data): void {
        echo "both __unserialize\n";
    }
    public function serialize() {
        echo "both legacy serialize\n";
        return 'legacy';
    }
    public function unserialize($data) {
        echo "both legacy unserialize($data)\n";
    }
}

class SharedMagic {
    public $child;
    public function __serialize(): array {
        return ['one' => $this->child, 'two' => $this->child];
    }
}

echo serialize(new MagicSerialize()), "\n";
echo serialize(new MagicSleep()), "\n";
echo serialize(new BadSleep()), "\n";
echo serialize(new DuplicateSleep()), "\n";
echo serialize(new NonStringSleep()), "\n";
try {
    serialize(new BadSerialize());
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
echo serialize(new Legacy()), "\n";
echo serialize([new LegacyNull(), new stdClass()]), "\n";
echo serialize(new Both()), "\n";

$restored = unserialize(serialize(new MagicSerialize()));
echo $restored->a, "\n";
$sleepRestore = new MagicSleep();
$sleepRestore->keep = 7;
$restored = unserialize(serialize($sleepRestore));
echo $restored->keep, "\n";
$legacy = unserialize('C:6:"Legacy":7:{payload}');
echo $legacy->a, "\n";
$both = unserialize('C:4:"Both":6:{legacy}');
echo "both after C ok\n";
$both2 = unserialize('O:4:"Both":1:{s:1:"z";i:5;}');
echo "both after O ok\n";

$child = new stdClass();
$child->v = 3;
$shared = new SharedMagic();
$shared->child = $child;
echo serialize($shared), "\n";
echo serialize([$shared, $child]), "\n";
