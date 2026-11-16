<?php
// Conformance tests for Phase C: The date parser (timelib_strtotime, date_parse, scanner rules)

date_default_timezone_set('UTC');
$base = 1715774400; // 2024-05-15 12:00:00 UTC (Wednesday)

// 1. Special relative keywords
var_dump(strtotime('now', $base));
var_dump(strtotime('today', $base));
var_dump(strtotime('yesterday', $base));
var_dump(strtotime('tomorrow', $base));
var_dump(strtotime('noon', $base));
var_dump(strtotime('midnight', $base));

// 2. Relative units (numbers + units)
var_dump(strtotime('+1 day', $base));
var_dump(strtotime('-2 days', $base));
var_dump(strtotime('+3 weeks', $base));
var_dump(strtotime('-1 month', $base));
var_dump(strtotime('+2 years', $base));
var_dump(strtotime('+4 hours', $base));
var_dump(strtotime('-30 minutes', $base));
var_dump(strtotime('+45 seconds', $base));
var_dump(strtotime('+1000 ms', $base));
var_dump(strtotime('+500000 µs', $base));

// 3. Relative text words
var_dump(strtotime('next monday', $base));
var_dump(strtotime('last friday', $base));
var_dump(strtotime('this thursday', $base));
var_dump(strtotime('first day of next month', $base));
var_dump(strtotime('last day of this month', $base));
var_dump(strtotime('third sunday of november 2024', $base));
var_dump(strtotime('+ 2 days ago', $base));

// 4. Common date and time formats
var_dump(strtotime('2024-05-15 14:30:00', $base));
var_dump(strtotime('2024/05/15 14:30:00', $base));
var_dump(strtotime('05/15/2024', $base));
var_dump(strtotime('05/15/24', $base));
var_dump(strtotime('15-05-2024', $base));
var_dump(strtotime('15.05.2024', $base));
var_dump(strtotime('15 May 2024', $base));
var_dump(strtotime('May 15, 2024', $base));
var_dump(strtotime('2024-W20-3', $base));
var_dump(strtotime('20240515143000', $base));
var_dump(strtotime('@' . $base));

// 5. Timezones and offsets
var_dump(strtotime('2024-05-15 14:30:00 +02:00', $base));
var_dump(strtotime('2024-05-15 14:30:00 -0400', $base));
var_dump(strtotime('2024-05-15 14:30:00 +0530', $base));
var_dump(strtotime('2024-05-15 14:30:00 UTC', $base));
var_dump(strtotime('2024-05-15 14:30:00 GMT', $base));
var_dump(strtotime('2024-05-15 14:30:00 Europe/Berlin', $base));
var_dump(strtotime('2024-05-15 14:30:00 America/New_York', $base));
var_dump(strtotime('2024-05-15 14:30:00 CEST', $base));
var_dump(strtotime('2024-05-15 14:30:00 EDT', $base));

// 6. Compound date + relative
var_dump(strtotime('2024-05-15 +1 day', $base));
var_dump(strtotime('2024-05-15 10:00:00 +2 hours', $base));
var_dump(strtotime('next monday 15:00', $base));

// 7. Invalid dates
var_dump(strtotime('not a date', $base));
var_dump(strtotime('2024-99-99', $base));

// 8. date_parse
var_dump(date_parse('2024-05-15 14:30:00.123456 Europe/Berlin'));
var_dump(date_parse('next Monday +2 days'));
var_dump(date_parse('yesterday 17:00:00'));
var_dump(date_parse('invalid string'));
var_dump(date_parse('2024-02-29 12:00:00 UTC'));
var_dump(date_parse('2024-02-30 12:00:00 UTC')); // invalid day warning
