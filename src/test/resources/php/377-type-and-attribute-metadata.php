<?php
// Type declarations and attributes are parsed as metadata; program output is unchanged.
namespace Fixture;

use Fixture\Attributes\Marker as M;

#[M(name: 'function', options: ['a' => 1])]
#[M('extra'), M(flag: true)]
function combine(
    #[M] ?int $first,
    #[M('second')] string|int|null $second = null,
    #[M] ?callable $callback = null,
    int &$byRef,
    string ...$rest
): ?string {
    $byRef++;
    $text = $first === null ? 'none' : (string) $first;
    if ($second !== null) {
        $text .= '/' . $second;
    }
    if ($callback !== null) {
        $text .= '/' . $callback($byRef);
    }
    return $text . '/' . implode(',', $rest);
}

#[M]
function shout(#[M] string $text): void {
    echo strtoupper($text), "\n";
}

#[M]
function fail(#[M] string $message): never {
    throw new \RuntimeException($message);
}

#[M]
interface Named {
    #[M]
    public function name(#[M] string $prefix = ''): string;
}

#[M]
trait Greeting {
    #[M]
    public ?string $greeting = null;

    #[M]
    public const int LIMIT = 10;

    #[M]
    public function greet(#[M] string $to): string {
        return ($this->greeting ?? 'hello') . ' ' . $to;
    }
}

#[M]
class Item implements Named {
    use Greeting;

    #[M]
    public static ?Item $current = null;

    #[M]
    public function __construct(
        #[M] public readonly int $id,
        #[M] protected ?string $name = null,
    ) {
        $this->greeting = 'hi';
    }

    #[M]
    public function name(#[M] string $prefix = ''): string {
        return $prefix . ($this->name ?? 'item');
    }

    #[M]
    public static function make(#[M] int $id): static {
        return new static($id, 'made');
    }

    #[M]
    public function with(#[M] Item $other): string {
        return $other->name('other:');
    }
}

#[M]
interface Counted {
    public function count(): int;
}

#[M]
class Bag implements Counted, \Countable {
    public function count(): int {
        return 3;
    }
}

#[M]
function describe(#[M] (Counted&\Countable)|null $value): string {
    return $value === null ? 'none' : 'bag:' . $value->count();
}

#[M]
enum Suit: string implements Named {
    #[M('hearts')]
    case Hearts = 'H';

    #[M('spades')]
    case Spades = 'S';

    #[M]
    public const int COUNT = 2;

    #[M]
    public function name(#[M] string $prefix = ''): string {
        return $prefix . $this->name;
    }
}

$byRef = 4;
echo combine(7, 'x', static fn(int $n): int => $n * 2, $byRef, 'a', 'b'), "\n";
echo combine(null, null, null, $byRef), "\n";
echo "byRef: ", $byRef, "\n";
shout('quiet');

try {
    fail('boom');
} catch (\RuntimeException $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}

$item = new Item(5, 'five');
echo $item->name(), ' ', $item->greet('there'), ' ', $item->id, "\n";
echo $item->with(new Item(6, 'six')), "\n";
echo Item::LIMIT, ' ', Item::make(9)->name(), "\n";
echo describe(new Bag()), ' ', describe(null), "\n";

$square = #[M] function (#[M] int $n): int {
    return $n * $n;
};
$label = #[M] fn(#[M] string $s): string => strtoupper($s);
echo $square(4), ' ', $label('ok'), "\n";

$anon = new #[M] class(3) {
    #[M]
    public function __construct(#[M] private int $value) {
    }

    #[M]
    public function value(): int {
        return $this->value;
    }
};
echo $anon->value(), "\n";

var_dump(Suit::COUNT);
echo class_exists('Fixture\\Suit') ? "suit\n" : "missing\n";
