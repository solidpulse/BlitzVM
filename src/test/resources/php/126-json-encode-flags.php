<?php
// json_encode: float formatting and every flag WordPress passes.
function e($v, $flags = 0, $depth = 512) {
    $r = json_encode($v, $flags, $depth);
    $code = json_last_error();
    var_dump($r);
    echo "  error ", $code, " ", json_last_error_msg(), "\n";
}

e([10.0, 1.5, 1e25, 0.1, -0.0, 0.30000000000000004, 1 / 3, -1.5e-7, 1e15, 1e16, 1e17, 1.0e-5, 0.0001]);
e([10.0, 2.5, 1e20], JSON_PRESERVE_ZERO_FRACTION);
e("a/b <tag> & 'apos' \"quot\"");
e("a/b", JSON_UNESCAPED_SLASHES);
e("<a href=\"x\">&'</a>", JSON_HEX_TAG | JSON_HEX_AMP | JSON_HEX_APOS | JSON_HEX_QUOT);
e("café 😀 ü");
e("café 😀 ü", JSON_UNESCAPED_UNICODE);
$terminators = 'line' . json_decode('" "', true) . 'sep' . json_decode('" "', true);
e($terminators, JSON_UNESCAPED_UNICODE);
e($terminators, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_LINE_TERMINATORS);
e('ctl ' . chr(1) . chr(31) . ' del ' . chr(127));
e("tab\tnl\ncr\rbs" . chr(8) . 'ff' . chr(12));
e([]);
e([], JSON_FORCE_OBJECT);
e([1, [2, 3]], JSON_FORCE_OBJECT);
e([1 => 'a', 2 => 'b']);
e([0 => 'a', 1 => 'b']);
e(['x' => 1, 'y' => [true, false, null]]);
e(['a' => [], 'b' => [1, ['c' => []]], 'd' => 'x'], JSON_PRETTY_PRINT);
e([[]], JSON_PRETTY_PRINT);
e(['k' => []], JSON_PRETTY_PRINT | JSON_FORCE_OBJECT);
e(['3', '1.5', '1e3', 'abc', '0x1A', ' 12', '-7'], JSON_NUMERIC_CHECK);
e(['url' => 'https://example.org/a?b=1&c=2'], JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT);
e(NAN);
e([1, INF], JSON_PARTIAL_OUTPUT_ON_ERROR);
e([[1]], 0, 1);
e([1], 0, 1);
e(42);
e("42");
e(true);
e(null);
// wp_json_encode-style payload
e(['id' => 7, 'title' => ['rendered' => 'Hello “World”'], 'tags' => [1, 2], 'meta' => []], JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
