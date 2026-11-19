<?php
// Static property sites keep the declaration and accessibility per class. The value is the
// request's slot, shared by a parent and its subclasses when the property is inherited.

class ParentClass {
    public static $counter = 0;
    protected static $guarded = 'guarded';
    private static $hidden = 'hidden';

    public static function readHidden() {
        return self::$hidden;
    }

    public static function readGuarded() {
        return static::$guarded;
    }

    public static function writeGuarded($value) {
        static::$guarded = $value;
    }
}

class ChildClass extends ParentClass {
}

function bump() {
    ParentClass::$counter++;
}

function readThroughChild() {
    return ChildClass::$counter;
}

for ($i = 0; $i < 3; $i++) {
    bump();
    readThroughChild();
}
echo readThroughChild(), "\n";

ChildClass::$counter = 10;
echo ParentClass::$counter, "\n";
echo ParentClass::readHidden(), "\n";
echo ChildClass::readGuarded(), "\n";
ChildClass::writeGuarded('changed');
echo ParentClass::readGuarded(), "\n";

try {
    echo ParentClass::$hidden, "\n";
} catch (Error $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
try {
    echo ParentClass::$guarded, "\n";
} catch (Error $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
try {
    echo ParentClass::$missing, "\n";
} catch (Error $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}

trait Counts {
    private static $uses = 0;

    public static function tick() {
        return ++self::$uses;
    }
}

class UsesCounts {
    use Counts;
}

class AlsoUsesCounts {
    use Counts;
}

echo UsesCounts::tick(), UsesCounts::tick(), AlsoUsesCounts::tick(), UsesCounts::tick(), "\n";
