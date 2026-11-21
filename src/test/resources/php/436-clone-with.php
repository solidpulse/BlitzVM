<?php
class Point {
    public int $x = 1;
    public string $label = 'a';
    protected int $hidden = 2;
    private int $secret = 3;

    public function __clone() {
        echo "clone:", $this->x, ',', $this->label, "\n";
    }

    public function copyWith(array $values): static {
        return clone($this, $values);
    }

    public function readSecret(): int {
        return $this->secret;
    }
}

class ChildPoint extends Point {
    public function copyHidden(): static {
        return clone($this, ['hidden' => 20]);
    }
}

class Dto {
    public readonly int $id;
    public readonly string $name;

    public function __construct(int $id, string $name) {
        $this->id = $id;
        $this->name = $name;
    }

    public function withName(string $name): static {
        return clone($this, ['name' => $name]);
    }
}

readonly class Frozen {
    public function __construct(public int $value = 1) {
    }
}

class Magic {
    public array $seen = [];

    public function __set($name, $value) {
        $this->seen[] = $name . '=' . $value;
    }
}

class Typed {
    public int $count = 0;
}

enum Suit: string {
    case Hearts = 'H';
}

function show(string $label, callable $fn): void {
    try {
        $result = $fn();
        echo $label, '=', $result, "\n";
    } catch (Throwable $e) {
        echo $label, '!', get_class($e), ': ', $e->getMessage(), "\n";
    }
}

$plain = new Point();
$copy = clone($plain);
echo $copy->x, ' ', $copy->label, ' ', $copy->readSecret(), "\n";

$changed = clone($plain, ['x' => 10, 'label' => 'b']);
echo $changed->x, ' ', $changed->label, ' ', $changed->readSecret(), "\n";

$fromMethod = $plain->copyWith(['x' => 7]);
echo $fromMethod->x, ' ', $fromMethod->label, "\n";

$hidden = (new ChildPoint())->copyHidden();
echo $hidden->x, ' ', $hidden->label, "\n";
echo (new Point())->copyWith(['secret' => 30])->readSecret(), "\n";

$dto = new Dto(1, 'one');
$renamed = $dto->withName('two');
echo $renamed->id, ' ', $renamed->name, "\n";

show('dto-global', function () use ($dto) {
    return clone($dto, ['name' => 'nope']);
});
show('protected-global', function () use ($plain) {
    $copy = clone($plain, ['hidden' => 5]);
    return $copy->x;
});
show('private-global', function () use ($plain) {
    $copy = clone($plain, ['secret' => 5]);
    return $copy->readSecret();
});
show('typed-bad', function () use ($plain) {
    $copy = clone($plain, ['x' => 'ten']);
    return $copy->x;
});
show('non-array-string', fn () => clone(new Point(), 'x'));
show('non-array-null', fn () => clone(new Point(), null));
show('readonly-class-known', function () {
    $copy = clone(new Frozen(), ['value' => 9]);
    return $copy->value;
});
show('readonly-class-unknown', fn () => clone(new Frozen(), ['extra' => 9]));

$magic = clone(new Magic(), ['dynamic' => 1]);
echo implode(',', $magic->seen), "\n";

$dynamic = clone(new Point(), ['extra' => 42, 0 => 'zero']);
echo $dynamic->extra, ' ', $dynamic->{'0'}, "\n";

$enumHolder = clone(new Point(), ['tag' => Suit::Hearts]);
echo $enumHolder->tag->name, ' ', $enumHolder->tag->value, "\n";

$reference = 3;
show('by-reference', function () use ($reference) {
    $copy = clone(new Typed(), ['count' => &$reference]);
    return $copy->count;
});

function cloneTarget() {
    echo 'target,';
    return new Point();
}

function cloneValues() {
    echo "values\n";
    return ['x' => 5];
}

clone(cloneTarget(), cloneValues());
