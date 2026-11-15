<?php
require_once __DIR__ . '/inc/throw_helper.php';

function strip($s) {
    return str_replace(__DIR__ . '/', '', $s);
}

class Service {
    public static function runStatic($v) {
        return (new Service())->runInstance($v);
    }

    public function runInstance($v) {
        return includedThrower($v);
    }
}

// 1. Across static method -> instance method -> included function
try {
    Service::runStatic("alpha");
} catch (OverflowException $e) {
    echo "caught1: ", $e->getMessage(), " code=", $e->getCode(), "
";
    echo strip($e->getTraceAsString()), "
";
}

// 2. Unwinding through foreach, switch, and nested while/for loops with finally blocks
$events = [];
function unwindMaze($items) {
    global $events;
    foreach ($items as $k => $v) {
        try {
            switch ($v) {
                case "skip":
                    $events[] = "skip:$k";
                    break;
                case "explode":
                    for ($i = 0; $i < 3; $i++) {
                        try {
                            if ($i === 1) {
                                throw new UnexpectedValueException("kaboom@$k");
                            }
                            $events[] = "for:$i";
                        } finally {
                            $events[] = "for-fin:$i";
                        }
                    }
                    break;
                default:
                    $events[] = "ok:$v";
            }
        } finally {
            $events[] = "foreach-fin:$k";
        }
    }
}

try {
    unwindMaze(["a" => "first", "b" => "skip", "c" => "explode", "d" => "never"]);
} catch (UnexpectedValueException $e) {
    $events[] = "caught:" . $e->getMessage();
}
echo implode(" | ", $events), "
";

// 3. Throwing from top-level code of an included file
$THROW_AT_TOP_OF_INCLUDE = true;
try {
    include __DIR__ . '/inc/throw_helper.php';
} catch (UnderflowException $e) {
    echo "include top caught: ", $e->getMessage(), " in ", basename($e->getFile()), ":", $e->getLine(), "
";
    echo strip($e->getTraceAsString()), "
";
}

// 4. Including a file with a syntax error throws catchable ParseError
try {
    include __DIR__ . '/inc/syntax_err.php';
} catch (ParseError $pe) {
    echo "parse error caught: ", get_class($pe), " in ", basename($pe->getFile()), ":", $pe->getLine(), "
";
}
