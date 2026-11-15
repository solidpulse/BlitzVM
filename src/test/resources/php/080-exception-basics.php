<?php
function strip($s) {
    return str_replace(__DIR__ . '/', '', $s);
}

// 1. Basic Exception properties & getters
$e = new Exception("something broke", 42);
var_dump($e->getMessage(), $e->getCode(), basename($e->getFile()), $e->getLine(), $e->getPrevious());
var_dump($e instanceof Throwable, $e instanceof Exception, $e instanceof Error);
var_dump(class_exists('Exception'), interface_exists('Throwable'), class_exists('Error'));

// 2. Previous chaining
$e2 = new RuntimeException("outer", 99, $e);
var_dump($e2->getMessage(), $e2->getCode(), $e2->getPrevious() === $e);
echo strip((string)$e2), "
---
";

// 3. ErrorException
$ee = new ErrorException("warn", 3, E_WARNING, "virtual.php", 77, $e2);
var_dump($ee->getMessage(), $ee->getCode(), $ee->getSeverity(), $ee->getFile(), $ee->getLine(), $ee->getPrevious() === $e2);

$eeDef = new ErrorException("def");
var_dump($eeDef->getSeverity(), basename($eeDef->getFile()), $eeDef->getLine());

// 4. User subclass with custom constructor and extra properties
class AppException extends LogicException {
    private string $secret = "priv";
    protected string $ctx;
    public int $httpStatus;

    public function __construct(string $msg, string $ctx, int $status = 500, ?Throwable $prev = null) {
        $this->ctx = $ctx;
        $this->httpStatus = $status;
        parent::__construct($msg, $status, $prev);
    }

    public function context(): string {
        return $this->ctx . ":" . $this->secret . ":" . $this->message;
    }
}

function makeAppEx() {
    return new AppException("db down", "mysql", 503);
}

$ae = makeAppEx();
var_dump($ae->getMessage(), $ae->getCode(), $ae->context(), basename($ae->getFile()), $ae->getLine());
echo strip($ae->getTraceAsString()), "
";

// 5. Subclass that omits parent::__construct still has file, line, trace and defaults
class BareException extends Exception {
    public function __construct() {
    }
}
$be = new BareException();
var_dump($be->getMessage(), $be->getCode(), basename($be->getFile()), $be->getLine(), is_array($be->getTrace()));
