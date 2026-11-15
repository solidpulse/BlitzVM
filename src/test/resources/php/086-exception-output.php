<?php
function stripPaths($s) {
    $dir = __DIR__ . '/';
    // Replace string(N) "<dir>..." lengths in var_dump output so path length differences don't break byte comparison
    $lines = explode("
", $s);
    $out = [];
    foreach ($lines as $line) {
        if (strpos($line, $dir) !== false) {
            $replaced = str_replace($dir, '', $line);
            if (preg_match('/^(\s*string\()\d+(\) "(.*)")$/', $replaced, $m)) {
                $replaced = $m[1] . strlen($m[3]) . $m[2];
            }
            $out[] = $replaced;
        } else {
            $out[] = $line;
        }
    }
    return implode("
", $out);
}

class DetailedException extends RuntimeException {
    private string $token = "sec";
    protected int $retry = 3;
    public string $tag = "net";

    public function setVirtualOrigin() {
        $this->file = "virtual/origin.php";
        $this->line = 42;
    }
}

function makeEx() {
    $root = new LogicException("root cause", 10);
    $root->getMessage(); // touch getter
    $ex = new DetailedException("request failed", 502, $root);
    return $ex;
}

$ex = makeEx();
echo "=== get_class ===
";
echo get_class($ex), " / ", get_class($ex->getPrevious()), "
";

echo "=== print_r ===
";
echo stripPaths(print_r($ex, true));

// Normalize virtual file on an unchained exception for exact var_dump property order check
$plain = new DetailedException("plain msg", 7);
$plain->setVirtualOrigin();
echo "=== print_r plain ===
";
echo stripPaths(print_r($plain, true));

echo "=== getTraceAsString ===
";
echo stripPaths($ex->getTraceAsString()), "
";

echo "=== __toString ===
";
echo stripPaths((string)$ex), "
";

// Empty message formatting in __toString
$empty = new Error("");
echo "=== empty msg __toString ===
";
echo stripPaths((string)$empty), "
";

// Error print_r property order (private keys under Error)
$err = new TypeError("bad type", 5);
echo "=== TypeError print_r ===
";
echo stripPaths(print_r($err, true));

// Cloning an exception throws uncloneable Error
try {
    $c = clone $ex;
} catch (Error $ce) {
    echo "clone error: ", get_class($ce), ": ", $ce->getMessage(), "
";
}
