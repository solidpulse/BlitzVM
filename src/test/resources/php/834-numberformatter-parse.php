<?php
// NumberFormatter::parse() returns the numeric prefix and reports U_PARSE_ERROR when there
// is no number at the start.
$f = new NumberFormatter('en', NumberFormatter::DECIMAL);
var_dump($f->parse('42', NumberFormatter::TYPE_INT64));
var_dump($f->parse('42', NumberFormatter::TYPE_INT32));
var_dump($f->parse('42', NumberFormatter::TYPE_DOUBLE));
var_dump($f->parse('1,234.56', NumberFormatter::TYPE_DOUBLE));
var_dump($f->parse('5 apples', NumberFormatter::TYPE_INT32));
var_dump($f->parse('.5', NumberFormatter::TYPE_DOUBLE));
var_dump($f->parse('  7 ', NumberFormatter::TYPE_INT32));
var_dump($f->getErrorCode(), $f->getErrorMessage());
$f->parse('abc', NumberFormatter::TYPE_DOUBLE);
var_dump($f->getErrorCode(), $f->getErrorMessage());
foreach ([NumberFormatter::TYPE_DEFAULT, NumberFormatter::TYPE_CURRENCY] as $type) {
    try {
        $f->parse('1', $type);
    } catch (Throwable $e) {
        echo get_class($e), ': ', $e->getMessage(), "\n";
    }
}
