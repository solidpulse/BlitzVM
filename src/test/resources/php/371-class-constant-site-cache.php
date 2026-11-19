<?php
// Class constant sites keep literals and ::class per class. Initializers still run once per
// request, unknown names still throw, and a static:: site shared by many subclasses must
// fall back to the ordinary lookup without losing a value.

define('RUNTIME_SUFFIX', '-dynamic');

interface Labelled {
    const LABEL = 'iface';
}

class Base implements Labelled {
    const NAME = 'base';
    const COUNT = 7;
    const COMPUTED = self::NAME . RUNTIME_SUFFIX;
    protected const HIDDEN = 'inside';

    public static function label() {
        return static::NAME;
    }

    public static function computed() {
        return static::COMPUTED;
    }

    public static function hidden() {
        return self::HIDDEN;
    }
}

class One extends Base {
    const NAME = 'one';
}

class Two extends Base {
    const NAME = 'two';
}

class Three extends Base {
    const NAME = 'three';
}

class Four extends Base {
    const NAME = 'four';
}

class Five extends Base {
    const NAME = 'five';
}

function literal() {
    return [Base::NAME, Base::COUNT, Base::LABEL, Base::class, One::NAME, Two::LABEL];
}

function literalAgain() {
    return [One::COUNT, Two::COUNT, Three::NAME, Four::NAME, Five::class];
}

function computed() {
    return [Base::COMPUTED, One::COMPUTED, Two::COMPUTED];
}

for ($i = 0; $i < 100; $i++) {
    literal();
    literalAgain();
    computed();
    Base::label();
    One::label();
    Two::label();
    Three::label();
    Four::label();
    Five::label();
    Base::hidden();
}

var_dump(literal());
var_dump(literalAgain());
var_dump(computed());
var_dump(Base::label(), One::label(), Two::label(), Three::label(), Four::label(), Five::label());
var_dump(Base::computed(), Five::computed(), Base::hidden());

try {
    var_dump(Base::MISSING);
} catch (Error $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
try {
    var_dump(One::MISSING);
} catch (Error $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
