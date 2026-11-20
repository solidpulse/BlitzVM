<?php
// Error details: messages, the "called in" suffix, and getFile()/getLine().
function strip($s) {
    return str_replace(__DIR__ . '/', '', $s);
}

function report(Throwable $e) {
    echo get_class($e), '|', basename($e->getFile()), ':', $e->getLine(), '|', strip($e->getMessage()), "\n";
}

function takesInt(int $x) { return $x; }

try { takesInt('x'); } catch (TypeError $e) { report($e); }

class Handler {
    public function method(int $x): array { return [$x]; }
    public static function staticMethod(string $x): string { return $x; }
    public int $property;
    public function returnWrong(): int { return 'not an int'; }
}

$handler = new Handler();
try { $handler->method('x'); } catch (TypeError $e) { report($e); }
try { Handler::staticMethod([]); } catch (TypeError $e) { report($e); }
$closure = function (int $x): int { return $x; };
try { $closure('x'); } catch (TypeError $e) { report($e); }
try { $handler->returnWrong(); } catch (TypeError $e) { report($e); }
try { $handler->property = 'x'; } catch (TypeError $e) { report($e); }
try { var_dump($handler->property); } catch (Error $e) { report($e); }
unset($handler->property);
try { var_dump($handler->property); } catch (Error $e) { report($e); }

function nested(int $x) { return $x; }
function caller($x) {
    return nested($x);
}
try { caller('x'); } catch (TypeError $e) { report($e); }

try {
    $handler->method([]);
} catch (TypeError $e) {
    echo 'index 1 ', strip($e->getMessage()), "\n";
}
