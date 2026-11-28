<?php
function withRef(&$value, $suffix = "!") {
    $value .= $suffix;
    return $value;
}

$s = "a";
echo withRef(suffix: "?", value: $s), " s=$s\n";
$s2 = "a";
echo withRef(value: $s2), " s2=$s2\n";
$data = ["k" => "v"];
echo withRef(value: $data["k"], suffix: "+"), " data=" . $data["k"] . "\n";

class Box {
    public function __construct(public string $value = "b") {}
}
$box = new Box();
echo withRef(value: $box->value, suffix: "."), " box={$box->value}\n";

$pos = "p";
echo withRef($pos, suffix: "~"), " pos=$pos\n";

function refVariadic(&...$rest) {
    foreach ($rest as $key => &$value) {
        $value .= "!";
    }
    return json_encode($rest);
}
$a = "a";
$b = "b";
echo refVariadic(second: $b, first: $a), "\n";
echo "$a $b\n";

$matches = null;
preg_match(pattern: "/b/", subject: "abc", matches: $matches);
print_r($matches);

$n = 0;
echo str_replace(search: "a", replace: "b", subject: "aaa", count: $n), " n=$n\n";

$sorted = [3, 1, 2];
sort(array: $sorted);
echo implode(",", $sorted), "\n";

$shifted = [4, 5, 6];
echo array_shift(array: $shifted), " ", implode(",", $shifted), "\n";

try {
    withRef(value: "literal" . "s");
} catch (Error $e) {
    echo get_class($e), ": ", $e->getMessage(), "\n";
}
try {
    preg_match(pattern: "/a/", subject: "abc", matches: []);
} catch (Error $e) {
    echo get_class($e), ": ", $e->getMessage(), "\n";
}

class RefMethod {
    public function bump(&$value, $by = 1) {
        $value += $by;
        return $value;
    }
}
$m = new RefMethod();
$count = 10;
echo $m->bump(by: 5, value: $count), " count=$count\n";
$name = "n";
echo RefMethod::class, "\n";

$reflection = new ReflectionClass(objectOrClass: Box::class);
echo $reflection->getName(), "\n";
$method = new ReflectionMethod(objectOrMethod: Box::class, method: "__construct");
echo $method->getName(), "\n";
$property = new ReflectionProperty(class: Box::class, property: "value");
echo $property->getName(), "\n";
