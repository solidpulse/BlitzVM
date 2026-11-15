<?php

interface Config {
    public const VERSION = '1.0';
    public const EXTRA = 40 + 2;
}

class BaseCfg implements Config {
    public const ONE = 1;
    public const TWO = self::ONE + 1;
    protected const PROT = 'prot';
    private const PRIV = 'priv';
    final public const LOCKED = 'fixed';
    public const OVERRIDDEN = 'base';

    public static function info(): array {
        return [
            self::class,
            static::class,
            self::OVERRIDDEN,
            static::OVERRIDDEN,
            self::PRIV,
            __CLASS__,
            __METHOD__,
        ];
    }
}

class SubCfg extends BaseCfg {
    public const OVERRIDDEN = 'sub';

    public static function subInfo(): array {
        return [
            self::class,
            parent::class,
            static::class,
            parent::OVERRIDDEN,
            static::OVERRIDDEN,
            parent::PROT,
            self::LOCKED,
            self::VERSION,
            self::EXTRA,
            __CLASS__,
            __METHOD__,
        ];
    }
}

$b = new BaseCfg();
$s = new SubCfg();

echo BaseCfg::TWO, '|', SubCfg::VERSION, '|', Config::EXTRA, '|', SubCfg::LOCKED, "\n";
echo BaseCfg::class, '|', $b::class, '|', $s::class, '|', NotLoadedYet::class, "\n";
print_r(BaseCfg::info());
print_r(SubCfg::info());
print_r(SubCfg::subInfo());
