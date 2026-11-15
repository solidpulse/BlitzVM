<?php

class ParentStatic {
    public static int $shared = 10;
    public static int $separate = 1;
    public static array $log = [];

    public static function whoSelf(): string {
        return self::tag();
    }

    public static function whoStatic(): string {
        return static::tag();
    }

    public static function tag(): string {
        return 'Parent';
    }

    public static function record(string $msg): void {
        self::$log[] = static::tag() . ':' . $msg;
    }
}

class ChildShared extends ParentStatic {
    public static function tag(): string {
        return 'ChildShared';
    }
}

class ChildOwn extends ParentStatic {
    public static int $separate = 100;

    public static function bumpBoth(): array {
        static::$separate += 5;
        parent::$separate += 2;
        return [static::$separate, parent::$separate];
    }

    public static function tag(): string {
        return 'ChildOwn';
    }
}

echo ParentStatic::$shared, ',', ChildShared::$shared, "\n";
ChildShared::$shared++;
echo ParentStatic::$shared, ',', ChildShared::$shared, "\n";
ParentStatic::$shared += 10;
echo ParentStatic::$shared, ',', ChildShared::$shared, "\n";

print_r(ChildOwn::bumpBoth());
echo ParentStatic::$separate, ',', ChildShared::$separate, ',', ChildOwn::$separate, "\n";

echo ChildShared::whoSelf(), '|', ChildShared::whoStatic(), "\n";
echo ChildOwn::whoSelf(), '|', ChildOwn::whoStatic(), "\n";

ParentStatic::record('a');
ChildShared::record('b');
ChildOwn::record('c');
print_r(ParentStatic::$log);

var_dump(isset(ParentStatic::$shared), isset(ParentStatic::$missing));
echo (ParentStatic::$missing ?? 'none'), "\n";
