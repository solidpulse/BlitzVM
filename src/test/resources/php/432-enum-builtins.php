<?php
set_error_handler(function ($no, $msg) {
    echo "ERR[$no]: $msg\n";
    return true;
});

enum Suit: string {
    case Hearts = 'H';
    case Spades = 'S';
}

enum Level: int {
    case Low = 1;
    case High = 10;
}

enum Pure {
    case A;
    case B;
}

class Wrapped {
    public function __construct(private string $value) {
    }
    public function __toString(): string {
        return $this->value;
    }
}

function show(string $label, callable $fn): void {
    try {
        $result = $fn();
        if ($result instanceof UnitEnum) {
            echo $label, '=', $result->name, "\n";
        } elseif ($result === null) {
            echo $label, "=null\n";
        } elseif ($result === false) {
            echo $label, "=false\n";
        } else {
            echo $label, '=', $result, "\n";
        }
    } catch (Throwable $e) {
        echo $label, '!', get_class($e), ': ', $e->getMessage(), "\n";
    }
}

echo implode(',', array_map(fn ($case) => $case->name, Suit::cases())), "\n";
echo implode(',', array_map(fn ($case) => $case->name, Level::cases())), "\n";
echo implode(',', array_map(fn ($case) => $case->name, Pure::cases())), "\n";
echo Suit::from('H')->name, ' ', Suit::from('S')->value, "\n";
echo Level::from(1)->name, ' ', Level::from(10)->name, "\n";
show('suit-try-h', fn () => Suit::tryFrom('H'));
show('suit-try-miss', fn () => Suit::tryFrom('Z'));
show('level-try-miss', fn () => Level::tryFrom(3));

show('suit-miss', fn () => Suit::from('Z'));
show('level-miss', fn () => Level::from(3));
show('level-string', fn () => Level::from('10'));
show('level-float', fn () => Level::from(10.0));
show('level-bool', fn () => Level::from(true));
show('level-null', fn () => Level::from(null));
show('suit-int', fn () => Suit::from(72));
show('suit-float', fn () => Suit::from(1.9));
show('suit-stringable', fn () => Suit::from(new Wrapped('S')));
show('level-stringable', fn () => Level::from(new Wrapped('1')));
show('level-bad-string', fn () => Level::from('ten'));
show('level-array', fn () => Level::from([]));

show('no-arg', fn () => Suit::from());
show('too-many', fn () => Level::tryFrom(1, 2));
show('cases-arg', fn () => Level::cases(1));
show('pure-from', fn () => Pure::from('A'));
show('pure-tryfrom', fn () => Pure::tryFrom('A'));

echo serialize(Suit::Hearts), ' ', serialize(Pure::B), "\n";
var_dump(Suit::Hearts);
var_dump(Level::High);
echo isset(Suit::Hearts->name) ? 'name-set' : 'name-unset', ' ',
     isset(Pure::A->value) ? 'value-set' : 'value-unset', "\n";
echo Suit::Hearts->name, ' ', Suit::Hearts->value, ' ', Level::High->value, "\n";
