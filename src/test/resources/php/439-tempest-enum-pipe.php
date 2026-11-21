<?php
namespace Tempest\Support\Arr {
    function wrap($value) {
        return is_array($value) ? $value : [$value];
    }

    function undot(array $items): array {
        $result = [];
        foreach ($items as $key => $value) {
            $parts = explode('.', $key);
            $cursor = &$result;
            foreach ($parts as $part) {
                $cursor[$part] ??= [];
                $cursor = &$cursor[$part];
            }
            $cursor = $value;
        }
        return $result;
    }
}

namespace Tempest\Mcp {
    use BackedEnum;
    use UnitEnum;
    use ValueError;

    enum ProtocolVersion: string {
        case VERSION_2025_11_25 = '2025-11-25';
        case VERSION_2025_06_18 = '2025-06-18';
        case VERSION_2025_03_26 = '2025-03-26';
        case VERSION_2024_11_05 = '2024-11-05';

        public const ProtocolVersion LATEST = self::VERSION_2025_11_25;

        public static function supported(): array {
            return self::cases();
        }

        public static function negotiate(?string $requested): self {
            if ($requested === null) {
                return self::LATEST;
            }

            return self::tryFrom($requested) ?? self::LATEST;
        }
    }

    $supported = ProtocolVersion::supported();
    echo count($supported), ' ', $supported[0]->value, "\n";
    echo ProtocolVersion::negotiate(null)->value, "\n";
    echo ProtocolVersion::negotiate('2025-06-18')->value, "\n";
    echo ProtocolVersion::negotiate('1999-01-01')->value, "\n";
    echo ProtocolVersion::LATEST->value, ' ',
        ProtocolVersion::LATEST instanceof UnitEnum ? 'unit' : '?', ' ',
        ProtocolVersion::LATEST instanceof BackedEnum ? 'backed' : '?', "\n";
}

namespace Tempest\Support {
    use UnitEnum;
    use ValueError;

    trait IsEnumHelper {
        public static function fromName(string $name): self {
            return static::tryFromName($name)
                ?? throw new ValueError(sprintf('"%s" is not a valid case name for enum "%s"', $name, static::class));
        }

        public static function tryFromName(string $name): ?static {
            $cases = array_filter(
                static::cases(),
                fn (UnitEnum $case) => $case->name === $name,
            );

            return array_shift($cases);
        }

        public function is(UnitEnum $enum): bool {
            return $this === $enum;
        }
    }

    enum Direction {
        use IsEnumHelper;

        case Up;
        case Down;
    }

    echo Direction::fromName('Up')->name, ' ', Direction::tryFromName('Nope') === null ? 'null' : '?', ' ',
        Direction::Down->is(Direction::Down) ? 'same' : '?', "\n";
}

namespace Tempest\Mapper {
    use function Tempest\Support\Arr\wrap;
    use function Tempest\Support\Arr\undot;

    $from = ['user.name' => 'Avinash', 'user.role' => 'admin'] |> wrap(...) |> undot(...);
    echo $from['user']['name'], ' ', $from['user']['role'], "\n";
    echo '  TEMPEST  ' |> trim(...) |> strtolower(...) |> ucfirst(...), "\n";
}
