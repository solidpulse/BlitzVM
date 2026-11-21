<?php
interface Labelled {
    public function label(): string;
}

#[Attribute(Attribute::TARGET_CLASS | Attribute::TARGET_CLASS_CONSTANT)]
class Tagged {
    public function __construct(public Suit $suit, public array $more = []) {
    }
}

enum Suit: string implements Labelled {
    case Hearts = 'H';
    case Spades = 'S';

    const Wild = self::Spades;
    const All = [self::Hearts, self::Spades];

    public function label(): string {
        return strtolower($this->name);
    }

    public static function pick(string $value): self {
        return self::from($value);
    }
}

enum Level: int {
    case Low = 1;
    case High = 10;
}

enum Pure {
    case A;
}

#[Tagged(Suit::Hearts, [Suit::Spades])]
enum Marked {
    case One;
    #[Tagged(Suit::Spades)]
    case Two;
}

function describe(Suit|Level|Pure $value): string {
    return match ($value) {
        Suit::Hearts => 'hearts',
        Suit::Spades => 'spades',
        Level::Low => 'low',
        Level::High => 'high',
        Pure::A => 'pure',
    };
}

echo Suit::Hearts->label(), ' ', Suit::pick('S')->label(), "\n";
echo describe(Suit::Hearts), ' ', describe(Level::High), ' ', describe(Pure::A), "\n";
var_dump(Suit::Wild === Suit::Spades, Suit::All[0] === Suit::Hearts);
var_dump(Suit::Hearts instanceof Suit, Suit::Hearts instanceof UnitEnum,
    Suit::Hearts instanceof BackedEnum, Suit::Hearts instanceof Labelled);
var_dump(Level::Low instanceof BackedEnum, Pure::A instanceof UnitEnum,
    Pure::A instanceof BackedEnum);
var_dump(is_subclass_of(Suit::class, UnitEnum::class),
    is_subclass_of(Suit::class, BackedEnum::class),
    is_subclass_of(Level::class, BackedEnum::class),
    is_subclass_of(Pure::class, BackedEnum::class));
var_dump(is_a(Suit::Hearts, 'UnitEnum'), is_a(Pure::A, 'UnitEnum'));
echo implode(',', class_implements(Suit::class)), "\n";

$marked = new ReflectionClass(Marked::class);
foreach ($marked->getAttributes() as $attribute) {
    $arguments = $attribute->getArguments();
    echo $attribute->getName(), ' ', $arguments[0] === Suit::Hearts ? 'h' : '?',
        ' ', $arguments[1][0] === Suit::Spades ? 's' : '?', "\n";
}
$caseAttributes = $marked->getReflectionConstant('Two')->getAttributes();
echo $caseAttributes[0]->getName(), ' ',
    $caseAttributes[0]->getArguments()[0] === Suit::Spades ? 's' : '?', "\n";

function show(string $label, callable $fn): void {
    try {
        $result = $fn();
        echo $label, '=', is_bool($result) ? ($result ? 'true' : 'false') : $result, "\n";
    } catch (Throwable $e) {
        echo $label, '!', get_class($e), ': ', $e->getMessage(), "\n";
    }
}

show('lt', fn () => Suit::Hearts < Suit::Spades);
show('gt', fn () => Suit::Hearts > Suit::Spades);
show('le', fn () => Suit::Hearts <= Suit::Spades);
show('ge-same', fn () => Suit::Hearts >= Suit::Hearts);
show('spaceship', fn () => Suit::Hearts <=> Suit::Spades);
show('spaceship-same', fn () => Suit::Hearts <=> Suit::Hearts);
show('eq', fn () => Suit::Hearts == Suit::Hearts);
show('eq-other', fn () => Suit::Hearts == Suit::Spades);
show('eq-cross-enum', fn () => Suit::Hearts == Level::Low);
show('new', function () { return new Suit(); });
show('clone', function () { return clone Suit::Hearts; });
show('write-name', function () { $case = Suit::Hearts; $case->name = 'x'; return $case; });
show('write-value', function () { $case = Suit::Hearts; $case->value = 'x'; return $case; });
show('write-dynamic', function () { $case = Suit::Hearts; $case->extra = 1; return $case; });
show('unset-name', function () { $case = Suit::Hearts; unset($case->name); return $case; });
show('unset-dynamic', function () { $case = Suit::Hearts; unset($case->extra); return 'ok'; });
show('read-dynamic', fn () => Suit::Hearts->extra);

var_dump((array) Suit::Hearts);
var_dump((array) Pure::A);
