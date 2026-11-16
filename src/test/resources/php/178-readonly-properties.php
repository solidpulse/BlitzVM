<?php
class Token {
    public readonly string $id;
    public function __construct(
        string $id,
        public readonly int $ttl = 60,
        private readonly ?array $scopes = ['read']
    ) {
        $this->id = $id;
    }
    public function scopesList(): string {
        return implode(',', $this->scopes ?? []);
    }
    public function overwriteId(): void {
        $this->id = 'other';
    }
}

$t = new Token('tok_1', scopes: ['read', 'write']);
echo "{$t->id}:{$t->ttl}:{$t->scopesList()}
";

try {
    $t->ttl = 120;
} catch (Error $e) {
    echo "mod_out=" . $e->getMessage() . "
";
}

try {
    $t->overwriteId();
} catch (Error $e) {
    echo "mod_in=" . $e->getMessage() . "
";
}

try {
    unset($t->id);
} catch (Error $e) {
    echo "unset=" . $e->getMessage() . "
";
}

try {
    $ref = &$t->id;
} catch (Error $e) {
    echo "ref=" . $e->getMessage() . "
";
}

readonly class ImmutablePair {
    public int $first;
    public function __construct(int $first, public int $second) {
        $this->first = $first;
    }
}

$pair = new ImmutablePair(11, 22);
echo "pair={$pair->first},{$pair->second}
";
try {
    $pair->first = 99;
} catch (Error $e) {
    echo "ro_cls=" . $e->getMessage() . "
";
}

class LazyReadonly {
    public readonly int $code;
}
$lazy = new LazyReadonly();
var_dump(isset($lazy->code));
try {
    echo $lazy->code;
} catch (Error $e) {
    echo "uninit=" . $e->getMessage() . "
";
}
