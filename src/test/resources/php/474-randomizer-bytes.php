<?php

final class SequenceEngine implements Random\Engine
{
    private array $values;
    private int $position = 0;

    public function __construct(string ...$values)
    {
        $this->values = $values;
    }

    public function generate(): string
    {
        $value = $this->values[$this->position % count($this->values)];
        $this->position++;
        return hex2bin($value);
    }
}

final class CountingEngine implements Random\Engine
{
    public int $calls = 0;
    public array $debug = [];

    public function generate(): string
    {
        $this->calls++;
        $this->debug[] = $this->calls;
        return str_repeat("\x5a", 8);
    }
}

function engine(int $seed): Random\Engine
{
    return new Random\Engine\Mt19937($seed);
}

$randomizer = new Random\Randomizer(engine(42));
echo "bytes";
foreach ([1, 2, 3, 4, 5, 7, 8, 9, 16, 17, 32, 33] as $length) {
    echo " ", bin2hex($randomizer->getBytes($length));
}
echo "\n";

foreach ([1, 2, 3, 8, 9, 16] as $length) {
    $randomizer = new Random\Randomizer(engine(42));
    echo "bytes $length ", bin2hex($randomizer->getBytes($length)), "\n";
}

foreach ([
    ["abcdefghij", 1],
    ["abcdefghij", 5],
    ["abcdefghij", 20],
    ["abc", 17],
    ["a", 5],
    ["ab", 7],
    ["abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789", 40],
    [implode('', array_map('chr', range(0, 127))), 30],
    [implode('', array_map('chr', range(0, 255))), 20],
    [implode('', array_map('chr', range(0, 255))), 20],
] as [$string, $length]) {
    $randomizer = new Random\Randomizer(engine(42));
    echo "gfbs ", strlen($string), " $length ", bin2hex($randomizer->getBytesFromString($string, $length)), "\n";
}

$long = '';
for ($i = 0; $i < 300; $i++) {
    $long .= chr($i % 256);
}
$randomizer = new Random\Randomizer(engine(42));
echo "gfbs long ", bin2hex($randomizer->getBytesFromString($long, 40)), "\n";

foreach ([0, 1, 2, 3, 5, 8, 13] as $count) {
    $randomizer = new Random\Randomizer(engine(42));
    $array = [];
    for ($i = 0; $i < $count; $i++) {
        $array[] = chr(97 + $i);
    }
    echo "shuffle $count ", bin2hex(implode('', $randomizer->shuffleArray($array))), "\n";
}

$randomizer = new Random\Randomizer(engine(42));
echo "shuffle assoc ", json_encode($randomizer->shuffleArray(['x' => 1, 'y' => 2, 'z' => 3])), "\n";

$randomizer = new Random\Randomizer(engine(42));
echo "pick single ", json_encode($randomizer->pickArrayKeys(['a'], 1)), " ", $randomizer->nextInt(), "\n";

foreach (["", "a", "ab", "abcdefghij", str_repeat("q", 30)] as $bytes) {
    $randomizer = new Random\Randomizer(engine(42));
    echo "shuffleBytes ", bin2hex($bytes), " ", bin2hex($randomizer->shuffleBytes($bytes)), "\n";
}

foreach ([
    [['a', 'b', 'c', 'd', 'e'], 1],
    [['a', 'b', 'c', 'd', 'e'], 2],
    [['a', 'b', 'c', 'd', 'e'], 3],
    [['a', 'b', 'c', 'd', 'e'], 4],
    [['a', 'b', 'c', 'd', 'e'], 5],
    [['x' => 1, 'y' => 2, 'z' => 3], 2],
    [['x' => 1, 'y' => 2, 'z' => 3], 3],
] as [$array, $count]) {
    $randomizer = new Random\Randomizer(engine(42));
    echo "pick $count ", json_encode($randomizer->pickArrayKeys($array, $count)), "\n";
}

try {
    (new Random\Randomizer(engine(42)))->getBytes(0);
} catch (ValueError $e) {
    echo $e->getMessage(), "\n";
}
try {
    (new Random\Randomizer(engine(42)))->getBytesFromString('', 1);
} catch (ValueError $e) {
    echo $e->getMessage(), "\n";
}
try {
    (new Random\Randomizer(engine(42)))->getBytesFromString('abc', 0);
} catch (ValueError $e) {
    echo $e->getMessage(), "\n";
}
try {
    (new Random\Randomizer(engine(42)))->pickArrayKeys([], 1);
} catch (ValueError $e) {
    echo $e->getMessage(), "\n";
}
try {
    (new Random\Randomizer(engine(42)))->pickArrayKeys([1, 2], 3);
} catch (ValueError $e) {
    echo $e->getMessage(), "\n";
}

$user = new SequenceEngine(
    '0123456789abcdef',
    'fedcba9876543210',
    '0001020304050607',
    '8899aabbccddeeff',
);
$randomizer = new Random\Randomizer($user);
echo "user nextInt ", $randomizer->nextInt(), " ", $randomizer->nextInt(), "\n";
$randomizer = new Random\Randomizer(new SequenceEngine('0123456789abcdef', 'fedcba9876543210'));
echo "user int ", $randomizer->getInt(0, 100), " ", $randomizer->getInt(0, PHP_INT_MAX), "\n";
$randomizer = new Random\Randomizer(new SequenceEngine('0123456789abcdef', 'fedcba9876543210'));
echo "user float ", bin2hex(pack('E', $randomizer->getFloat(0.0, 1.0))), "\n";
$randomizer = new Random\Randomizer(new SequenceEngine('0123456789abcdef', 'fedcba9876543210'));
echo "user bytes ", bin2hex($randomizer->getBytes(9)), "\n";
$randomizer = new Random\Randomizer(new SequenceEngine('0123456789abcdef', 'fedcba9876543210'));
echo "user shuffle ", json_encode($randomizer->shuffleArray([1, 2, 3, 4])), "\n";
$randomizer = new Random\Randomizer(new SequenceEngine('0123456789abcdef', 'fedcba9876543210'));
echo "user gfbs ", bin2hex($randomizer->getBytesFromString('abcdefghij', 10)), "\n";

$counting = new CountingEngine();
$randomizer = new Random\Randomizer($counting);
echo "calls ", $randomizer->nextInt(), " ", count($counting->debug), "\n";
$counting = new CountingEngine();
$randomizer = new Random\Randomizer($counting);
$randomizer->shuffleBytes('abcdef');
echo "calls shuffle ", $counting->calls, "\n";

$randomizer = new Random\Randomizer(new Random\Engine\Mt19937(42));
for ($i = 0; $i < 10; $i++) {
    $randomizer->getInt(0, 100);
}
$copy = unserialize(serialize($randomizer));
echo "serialize ", $copy->nextInt(), " ", $randomizer->nextInt(), "\n";
echo "serialize len ", strlen(serialize($randomizer)), "\n";

$randomizer = new Random\Randomizer(new Random\Engine\PcgOneseq128XslRr64(9));
echo "engine prop ", get_class($randomizer->engine), " ", bin2hex($randomizer->engine->generate()), "\n";
