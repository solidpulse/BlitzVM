<?php
// json_encode: common flags, floats, unicode, objects, JsonSerializable, depth errors and
// invalid UTF-8.
function e($label, $value, $flags = 0, $depth = 512) {
    $result = json_encode($value, $flags, $depth);
    printf("%s=%s err=%d %s\n", $label, $result === false ? 'false' : bin2hex($result),
        json_last_error(), json_last_error_msg());
}

class Plain {
    public $name = 'value';
    public $list = [1, 2, 3];
    protected $hidden = 'no';
    private $secret = 'no';
}
class WithToString {
    public $id = 7;
    public function __toString(): string { return 'text'; }
}
class Serializes implements JsonSerializable {
    public $id = 9;
    public function jsonSerialize(): mixed { return ['id' => $this->id, 'extra' => true]; }
}
class SelfSerializes implements JsonSerializable {
    public function jsonSerialize(): mixed { return $this; }
}

e('scalars', [null, true, false, 0, -1, 1.5]);
e('strings', ['plain', "quote\"backslash\\slash/", "<tag>&'apos'", "tab\tnl\ncr\rbs\x08ff\x0C", "\x01\x1F\x7F"]);
e('escapes', ["quote\"backslash\\slash/<tag>&'apos'"]);
e('hex', ["quote\"backslash\\slash/<tag>&'apos'"], JSON_HEX_TAG | JSON_HEX_AMP | JSON_HEX_APOS | JSON_HEX_QUOT);
e('unescaped', ["a/b <tag> & 'apos' \"q\" \xC3\xA9 \xE2\x98\x83"], JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
e('unicodenormal', ["caf\xC3\xA9 \xE2\x98\x83 \xF0\x9F\x98\x80"]);
e('line-terminators', ["a\xE2\x80\xA8b\xE2\x80\xA9c"], JSON_UNESCAPED_UNICODE);
e('line-terminators-un', ["a\xE2\x80\xA8b\xE2\x80\xA9c"], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_LINE_TERMINATORS);
e('floats', [0.0, -0.0, 1.0, 1.5, 0.1, 1 / 3, 1e25, -1.5e-7, 1e15, 1e16, 1e17, 1.0e-5, 0.0001]);
e('floats-preserve', [0.0, -0.0, 1.0, 1e15], JSON_PRESERVE_ZERO_FRACTION);
e('lists', [[], [1], [1, 2], [[1], [2, [3]]]]);
e('objects', new Plain());
e('tosring-object', new WithToString());
e('serializable', new Serializes());
e('self-serializable', new SelfSerializes());
e('object-list', [new WithToString(), new Serializes()]);
e('nested-keys', ['b' => 1, 'a' => ['d' => 2, 0 => 3], 5 => 4]);
e('force-object', [1, 2], JSON_FORCE_OBJECT);
e('force-object-nested', ['a' => [1, 2]], JSON_FORCE_OBJECT);
e('numeric-check', ['1', '01', '1.5', '1e3', 'abc', ' 12', '-7', '0x1A'], JSON_NUMERIC_CHECK);
e('pretty', ['a' => [1, 2], 'b' => []], JSON_PRETTY_PRINT);
e('pretty-empty', [[], [], [[]]], JSON_PRETTY_PRINT);
e('depth-ok', [[1]], 0, 2);
e('depth-error', [[1]], 0, 1);
e('depth-partial', [[1], [2]], JSON_PARTIAL_OUTPUT_ON_ERROR, 1);
e('invalid-utf8', ["a\xFFb\xC3"]);
e('invalid-utf8-partial', ["a\xFFb"], JSON_PARTIAL_OUTPUT_ON_ERROR);
e('invalid-utf8-substitute', ["a\xFFb"], JSON_INVALID_UTF8_SUBSTITUTE);
e('invalid-utf8-ignore', ["a\xFFb"], JSON_INVALID_UTF8_IGNORE);
e('nan-inf', [NAN, INF, -INF]);
e('nan-inf-partial', [1, NAN, 2], JSON_PARTIAL_OUTPUT_ON_ERROR);

$recursive = [];
$recursive['self'] = &$recursive;
e('recursive', $recursive, JSON_PARTIAL_OUTPUT_ON_ERROR);
try {
    json_encode($recursive, JSON_THROW_ON_ERROR);
    echo "throw=no\n";
} catch (Throwable $e) {
    echo 'throw=', get_class($e), ': ', $e->getMessage(), ' code=', $e->getCode(), "\n";
}
