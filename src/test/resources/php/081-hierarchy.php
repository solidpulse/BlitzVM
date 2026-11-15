<?php
$classes = [
    'Exception',
    'ErrorException',
    'LogicException',
    'BadFunctionCallException',
    'BadMethodCallException',
    'DomainException',
    'InvalidArgumentException',
    'LengthException',
    'OutOfRangeException',
    'RuntimeException',
    'OutOfBoundsException',
    'OverflowException',
    'RangeException',
    'UnderflowException',
    'UnexpectedValueException',
    'JsonException',
    'Error',
    'TypeError',
    'ArgumentCountError',
    'ValueError',
    'ArithmeticError',
    'DivisionByZeroError',
    'AssertionError',
    'CompileError',
    'ParseError',
    'UnhandledMatchError',
];

echo "Throwable interface: ", interface_exists('Throwable') ? "yes" : "no", "
";

foreach ($classes as $cls) {
    $obj = new $cls("m");
    printf("%s | exists=%d parent=%s throwable=%d exc=%d err=%d logic=%d runtime=%d type=%d arith=%d compile=%d
",
        get_class($obj),
        class_exists($cls) ? 1 : 0,
        var_export(get_parent_class($obj), true),
        $obj instanceof Throwable ? 1 : 0,
        $obj instanceof Exception ? 1 : 0,
        $obj instanceof Error ? 1 : 0,
        $obj instanceof LogicException ? 1 : 0,
        $obj instanceof RuntimeException ? 1 : 0,
        $obj instanceof TypeError ? 1 : 0,
        $obj instanceof ArithmeticError ? 1 : 0,
        $obj instanceof CompileError ? 1 : 0
    );
    if (!is_a($obj, 'Throwable') || !is_a($cls, 'Throwable', true)) {
        echo "FAIL is_a $cls
";
    }
}

class WP_Exception extends Exception {}
$wpe = new WP_Exception("wp");
var_dump($wpe instanceof Exception, $wpe instanceof Throwable, get_parent_class($wpe), is_subclass_of($wpe, 'Exception'));
