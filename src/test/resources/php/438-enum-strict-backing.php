<?php
declare(strict_types=1);

enum Level: int {
    case Low = 1;
}

enum Suit: string {
    case Hearts = 'H';
}

function show(string $label, callable $fn): void {
    try {
        $result = $fn();
        echo $label, '=', $result->name, "\n";
    } catch (Throwable $e) {
        echo $label, '!', get_class($e), ': ', $e->getMessage(), "\n";
    }
}

show('int', fn () => Level::from(1));
show('string', fn () => Suit::from('H'));
show('string-in-numeric', fn () => Level::from('1'));
show('float', fn () => Level::from(1.0));
show('bool', fn () => Level::from(true));
show('null', fn () => Level::from(null));
show('int-in-string', fn () => Suit::from(72));
show('try-string-in-numeric', fn () => Level::tryFrom('1'));
