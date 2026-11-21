<?php
$decimal = new NumberFormatter('en', NumberFormatter::DECIMAL);
var_dump($decimal->format(1234.5678), $decimal->format(-1234.5), $decimal->format(0), $decimal->format(12));

$decimal->setAttribute(NumberFormatter::MAX_FRACTION_DIGITS, 2);
var_dump($decimal->format(1234.5678), $decimal->getAttribute(NumberFormatter::MAX_FRACTION_DIGITS));

$decimal->setAttribute(NumberFormatter::FRACTION_DIGITS, 1);
var_dump($decimal->format(1234.5678), $decimal->format(2));

$percent = new NumberFormatter('en', NumberFormatter::PERCENT);
$percent->setAttribute(NumberFormatter::FRACTION_DIGITS, 0);
var_dump($percent->format(0.5), $percent->format(0.1234));

$currency = new NumberFormatter('en', NumberFormatter::CURRENCY);
var_dump($currency->formatCurrency(1234.5, 'USD'));

echo $decimal->getErrorMessage(), ' ', $decimal->getErrorCode(), "\n";
var_dump(NumberFormatter::DECIMAL, NumberFormatter::MAX_FRACTION_DIGITS, NumberFormatter::FRACTION_DIGITS);
