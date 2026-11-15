<?php
// Verify json_last_error is NOT clobbered when JSON_THROW_ON_ERROR is used
json_decode("{invalid");
echo "initial last_error: ", json_last_error(), " (", json_last_error_msg(), ")
";

// 1. json_decode errors with JSON_THROW_ON_ERROR
$badInputs = [
    'empty' => ['', 512, JSON_THROW_ON_ERROR],
    'syntax' => ['[1, 2,', 512, JSON_THROW_ON_ERROR],
    'ctrl' => ['"' . chr(9) . '"', 512, JSON_THROW_ON_ERROR],
    'utf16' => ['"\ud83d"', 512, JSON_THROW_ON_ERROR],
    'depth' => ['[[1]]', 2, JSON_THROW_ON_ERROR],
];

foreach ($badInputs as $name => $spec) {
    try {
        json_decode($spec[0], true, $spec[1], $spec[2]);
        echo "$name => NO THROW
";
    } catch (JsonException $e) {
        printf("decode %s => %s code=%d msg=%s (last_err=%d)
",
            $name, get_class($e), $e->getCode(), $e->getMessage(), json_last_error());
    }
}

// Valid decode with JSON_THROW_ON_ERROR succeeds and leaves json_last_error untouched
$ok = json_decode('{"k": 42}', true, 512, JSON_THROW_ON_ERROR);
echo "decode ok: ", $ok['k'], " last_err=", json_last_error(), "
";

// 2. json_encode errors with JSON_THROW_ON_ERROR
$badEncode = [
    'nan' => [NAN, JSON_THROW_ON_ERROR, 512],
    'inf' => [INF, JSON_THROW_ON_ERROR, 512],
    'depth' => [[[1]], JSON_THROW_ON_ERROR, 1],
];

foreach ($badEncode as $name => $spec) {
    try {
        json_encode($spec[0], $spec[1], $spec[2]);
        echo "$name => NO THROW
";
    } catch (JsonException $e) {
        printf("encode %s => %s code=%d msg=%s (last_err=%d)
",
            $name, get_class($e), $e->getCode(), $e->getMessage(), json_last_error());
    }
}

// PARTIAL_OUTPUT_ON_ERROR combined with JSON_THROW_ON_ERROR does not throw!
$partial = json_encode([1, NAN], JSON_THROW_ON_ERROR | JSON_PARTIAL_OUTPUT_ON_ERROR);
var_dump($partial);

// Valid encode with JSON_THROW_ON_ERROR
$encOk = json_encode(['a' => 1], JSON_THROW_ON_ERROR);
echo "encode ok: ", $encOk, " last_err=", json_last_error(), "
";
