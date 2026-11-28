<?php

final class EmptyEngine implements Random\Engine
{
    public function generate(): string
    {
        return '';
    }
}

final class ConstantEngine implements Random\Engine
{
    public string $bytes;
    public int $calls = 0;

    public function __construct(string $bytes)
    {
        $this->bytes = $bytes;
    }

    public function generate(): string
    {
        $this->calls++;
        return $this->bytes;
    }
}

$secure = new Random\Engine\Secure();
echo "secure ", get_class($secure), " ",
    $secure instanceof Random\CryptoSafeEngine ? "crypto" : "plain", " ",
    $secure instanceof Random\Engine ? "engine" : "none", " ",
    strlen($secure->generate()), "\n";

try {
    clone $secure;
} catch (Error $e) {
    echo $e->getMessage(), "\n";
}
try {
    serialize($secure);
} catch (Exception $e) {
    echo $e->getMessage(), "\n";
}
try {
    unserialize('O:20:"Random\Engine\Secure":0:{}');
} catch (Exception $e) {
    echo $e->getMessage(), "\n";
}

$randomizer = new Random\Randomizer();
echo "default ", get_class($randomizer->engine), "\n";
try {
    clone $randomizer;
} catch (Error $e) {
    echo $e->getMessage(), "\n";
}
try {
    $randomizer->extra = 1;
} catch (Error $e) {
    echo $e->getMessage(), "\n";
}
try {
    $randomizer->engine = $secure;
} catch (Error $e) {
    echo $e->getMessage(), "\n";
}

$empty = new EmptyEngine();
foreach ([
    'nextInt' => fn() => (new Random\Randomizer($empty))->nextInt(),
    'getBytes' => fn() => (new Random\Randomizer($empty))->getBytes(2),
    'getInt' => fn() => (new Random\Randomizer($empty))->getInt(0, 5),
    'getFloat' => fn() => (new Random\Randomizer($empty))->getFloat(0.0, 1.0),
    'getBytesFromString' => fn() => (new Random\Randomizer($empty))->getBytesFromString('abc', 2),
    'shuffleArray' => fn() => (new Random\Randomizer($empty))->shuffleArray([1, 2]),
    'shuffleBytes' => fn() => (new Random\Randomizer($empty))->shuffleBytes('abcd'),
    'pickArrayKeys' => fn() => (new Random\Randomizer($empty))->pickArrayKeys(['a', 'b'], 1),
] as $name => $call) {
    try {
        $call();
        echo "$name no error\n";
    } catch (Random\BrokenRandomEngineError $e) {
        echo "$name ", $e->getMessage(), "\n";
    }
}

foreach ([
    'getInt' => fn() => (new Random\Randomizer(new ConstantEngine("\xff\xff\xff\xff")))->getInt(0, 2),
    'getFloat' => fn() => (new Random\Randomizer(new ConstantEngine(str_repeat("\xff", 8))))->getFloat(0.0, 1.0),
    'getBytesFromString' => fn() => (new Random\Randomizer(new ConstantEngine("\xff")))->getBytesFromString('abc', 2),
    'pickArrayKeys' => fn() => (new Random\Randomizer(new ConstantEngine(str_repeat("\xff", 8))))->pickArrayKeys(['a', 'b', 'c'], 1),
] as $name => $call) {
    try {
        $call();
        echo "$name no error\n";
    } catch (Random\BrokenRandomEngineError $e) {
        echo "$name ", $e->getMessage(), "\n";
    }
}

$engine = new ConstantEngine("\x00\x01\x02\x03");
$randomizer = new Random\Randomizer($engine);
echo "chunk4 ", $randomizer->nextInt(), " ", bin2hex($randomizer->getBytes(6)), " ",
    $engine->calls, "\n";

$engine = new ConstantEngine("\x00\x01\x02\x03\x04\x05\x06\x07\x08\x09");
$randomizer = new Random\Randomizer($engine);
echo "chunk10 ", bin2hex($randomizer->getBytes(10)), " ", $randomizer->nextInt(), " ",
    $engine->calls, "\n";

$engine = new ConstantEngine("\xff");
$randomizer = new Random\Randomizer($engine);
echo "chunk1 ", bin2hex($randomizer->getBytesFromString('abcd', 10)), " ",
    $randomizer->nextInt(), " ", $engine->calls, "\n";

$engine = new Random\Engine\Mt19937(42);
try {
    $engine->__serialize(1);
} catch (ArgumentCountError $e) {
    echo $e->getMessage(), "\n";
}
try {
    $engine->__debugInfo(1);
} catch (ArgumentCountError $e) {
    echo $e->getMessage(), "\n";
}
try {
    $engine->__unserialize(null);
} catch (TypeError $e) {
    echo $e->getMessage(), "\n";
}
try {
    $engine->__unserialize([[], ['bad']]);
} catch (Exception $e) {
    echo $e->getMessage(), "\n";
}

$randomizer = new Random\Randomizer(new Random\Engine\Mt19937(42));
try {
    $randomizer->__serialize(1);
} catch (ArgumentCountError $e) {
    echo $e->getMessage(), "\n";
}
try {
    $randomizer->__unserialize('x');
} catch (TypeError $e) {
    echo $e->getMessage(), "\n";
}
try {
    $randomizer->__unserialize([]);
} catch (Exception $e) {
    echo $e->getMessage(), "\n";
}
try {
    $randomizer->__unserialize([['engine' => 5]]);
} catch (Exception $e) {
    echo $e->getMessage(), "\n";
}

foreach ([
    'getBytes 0' => fn() => (new Random\Randomizer())->getBytes(0),
    'getBytes object' => fn() => (new Random\Randomizer())->getBytes(new stdClass()),
    'getBytesFromString empty' => fn() => (new Random\Randomizer())->getBytesFromString('', 1),
    'getBytesFromString zero' => fn() => (new Random\Randomizer())->getBytesFromString('abc', 0),
    'getBytesFromString array' => fn() => (new Random\Randomizer())->getBytesFromString([], 1),
    'getBytesFromString object' => fn() => (new Random\Randomizer())->getBytesFromString(new stdClass(), 1),
    'pickArrayKeys empty' => fn() => (new Random\Randomizer())->pickArrayKeys([], 1),
    'pickArrayKeys too many' => fn() => (new Random\Randomizer())->pickArrayKeys([1, 2], 3),
    'pickArrayKeys object' => fn() => (new Random\Randomizer())->pickArrayKeys(new stdClass(), 1),
    'shuffleArray object' => fn() => (new Random\Randomizer())->shuffleArray(new stdClass()),
    'shuffleBytes object' => fn() => (new Random\Randomizer())->shuffleBytes(new stdClass()),
    'getInt reversed' => fn() => (new Random\Randomizer())->getInt(5, 1),
    'getFloat equal' => fn() => (new Random\Randomizer())->getFloat(1.0, 1.0),
    'getFloat infinite' => fn() => (new Random\Randomizer())->getFloat(INF, 1.0),
    'getFloat nan' => fn() => (new Random\Randomizer())->getFloat(0.0, NAN),
    'getFloat empty open' => fn() => (new Random\Randomizer())->getFloat(1.0, 1.0000000000000002, Random\IntervalBoundary::OpenOpen),
    'getFloat boundary' => fn() => (new Random\Randomizer())->getFloat(0.0, 1.0, 5),
    'getFloat too many' => fn() => (new Random\Randomizer())->getFloat(0.0, 1.0, Random\IntervalBoundary::ClosedOpen, 1),
    'getInt one' => fn() => (new Random\Randomizer())->getInt(1),
    'nextInt one' => fn() => (new Random\Randomizer())->nextInt(1),
    'getBytes zero args' => fn() => (new Random\Randomizer())->getBytes(),
    'pickArrayKeys one' => fn() => (new Random\Randomizer())->pickArrayKeys([1]),
] as $name => $call) {
    try {
        $call();
        echo "$name no error\n";
    } catch (Throwable $e) {
        echo "$name ", get_class($e), ": ", $e->getMessage(), "\n";
    }
}

echo "boundaries ", implode(',', array_map(fn($c) => $c->name, Random\IntervalBoundary::cases())), "\n";
echo "hierarchy ",
    (new Random\BrokenRandomEngineError()) instanceof Random\RandomError ? "error" : "plain", " ",
    (new Random\BrokenRandomEngineError()) instanceof Error ? "throwable" : "not", " ",
    (new Random\RandomException('x')) instanceof Exception ? "exception" : "not", "\n";

mt_srand(42);
echo "nulls ", mt_rand(null, null), " ", rand(null, null), "\n";

mt_srand(1.9);
echo "floatseed ", mt_rand(), " ", mt_rand(0, 100), "\n";

mt_srand(true);
echo "boolseed ", mt_rand(), "\n";

mt_srand("42");
echo "stringseed ", mt_rand(), "\n";

mt_srand(42);
echo "single ", array_rand([7], 1), " ", mt_rand(), "\n";

try {
    mt_srand("abc");
} catch (TypeError $e) {
    echo $e->getMessage(), "\n";
}
try {
    array_rand([1, 2, 3], 'x');
} catch (TypeError $e) {
    echo $e->getMessage(), "\n";
}
try {
    array_rand([1, 2, 3], new stdClass());
} catch (TypeError $e) {
    echo $e->getMessage(), "\n";
}
try {
    random_int(new stdClass(), 1);
} catch (TypeError $e) {
    echo $e->getMessage(), "\n";
}
try {
    random_bytes([]);
} catch (TypeError $e) {
    echo $e->getMessage(), "\n";
}
try {
    str_shuffle(new stdClass());
} catch (TypeError $e) {
    echo $e->getMessage(), "\n";
}
try {
    shuffle(5);
} catch (Error $e) {
    echo $e->getMessage(), "\n";
}
try {
    mt_rand(1);
} catch (ArgumentCountError $e) {
    echo $e->getMessage(), "\n";
}
try {
    rand(1, 2, 3);
} catch (ArgumentCountError $e) {
    echo $e->getMessage(), "\n";
}

srand(1);
$value = lcg_value();
var_dump(is_float($value), $value >= 0.0, $value < 1.0);
