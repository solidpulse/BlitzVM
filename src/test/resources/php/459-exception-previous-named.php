<?php
$inner = new RuntimeException('inner');
$outer = new Exception(message: 'outer', previous: $inner);

var_dump($outer->getMessage(), $outer->getPrevious() === $inner, $outer->getPrevious()->getMessage());

$error = new Error(message: 'wrapped', previous: $outer);
var_dump($error->getMessage(), $error->getPrevious() === $outer, $error->getPrevious()->getPrevious() === $inner);

$errorException = new ErrorException(message: 'warning', code: 2, severity: E_WARNING, filename: 'file.php', line: 7, previous: $inner);
var_dump($errorException->getMessage(), $errorException->getCode(), $errorException->getSeverity());
var_dump($errorException->getFile(), $errorException->getLine(), $errorException->getPrevious() === $inner);

$defaulted = new Exception(message: 'only message');
var_dump($defaulted->getMessage(), $defaulted->getCode(), $defaulted->getPrevious());
