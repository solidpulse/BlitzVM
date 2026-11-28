<?php
define('DEF_CONST', 5);
const TOP_CONST = 7;
final class Config {
    const INNER_CONST = 3;
    public int $a = TOP_CONST;
    public int $b = self::INNER_CONST;
    public static int $s = DEF_CONST;
    public function show(int $x = TOP_CONST, int $y = self::INNER_CONST, string $z = 'z'): string {
        return "$x:$y:$z:{$this->a}:{$this->b}:" . self::$s;
    }
}
$c = new Config();
echo $c->show(), "\n";
echo $c->show(1), "\n";
echo Config::$s, "\n";
