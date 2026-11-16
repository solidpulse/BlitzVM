<?php
// Conformance tests for Phase B: Core arithmetic, date, gmdate, mktime, gmmktime, idate, getdate, localtime, checkdate

// 1. checkdate
var_dump(checkdate(2, 29, 2024)); // leap year: true
var_dump(checkdate(2, 29, 2023)); // non-leap year: false
var_dump(checkdate(4, 31, 2024)); // 30 days in April: false
var_dump(checkdate(12, 31, 2024)); // valid: true
var_dump(checkdate(0, 1, 2024)); // invalid month: false
var_dump(checkdate(13, 1, 2024)); // invalid month: false
var_dump(checkdate(1, 0, 2024)); // invalid day: false
var_dump(checkdate(1, 32, 2024)); // invalid day: false
var_dump(checkdate(1, 1, 0)); // invalid year (<1): false
var_dump(checkdate(1, 1, 32768)); // invalid year (>32767): false

// 2. timezone get/set
var_dump(date_default_timezone_get());
var_dump(date_default_timezone_set('Europe/Berlin'));
var_dump(date_default_timezone_get());
var_dump(@date_default_timezone_set('Invalid/Timezone_Name_123'));
var_dump(date_default_timezone_get());

// 3. mktime and gmmktime
date_default_timezone_set('UTC');
var_dump(mktime(12, 34, 56, 7, 8, 2024));
var_dump(gmmktime(12, 34, 56, 7, 8, 2024));
// 2-digit years
var_dump(gmmktime(0, 0, 0, 1, 1, 24)); // 2024
var_dump(gmmktime(0, 0, 0, 1, 1, 99)); // 1999
// Roll over / overflow normalization
var_dump(gmmktime(25, 61, 61, 13, 32, 2024));

// 4. date and gmdate formatting
$ts = 1711846800; // 2024-03-31 01:00:00 UTC (European DST transition day)
date_default_timezone_set('UTC');
var_dump(gmdate('Y-m-d H:i:s T P', $ts));
var_dump(date('Y-m-d H:i:s T P', $ts));

date_default_timezone_set('Europe/Berlin');
var_dump(date('Y-m-d H:i:s T P I e', $ts));
var_dump(date('Y-m-d H:i:s T P I e', $ts + 7200));

date_default_timezone_set('Australia/Lord_Howe');
var_dump(date('Y-m-d H:i:s T P e', 0));
var_dump(date('Y-m-d H:i:s T P e', -86400));

// All format characters & escaping
date_default_timezone_set('UTC');
var_dump(date('d D j l N S w z W F m M n t L o Y y a A B g G h H i s u v e I O P p T Z c r U', 1700000000));
var_dump(date('\T\o\d\a\y \i\s Y-m-d', 1700000000));

// Expanded years x and X
var_dump(date('x X', 1700000000));

// 5. idate
date_default_timezone_set('Europe/Berlin');
$idate_ts = 1700000000;
var_dump(idate('d', $idate_ts));
var_dump(idate('j', $idate_ts));
var_dump(idate('N', $idate_ts));
var_dump(idate('w', $idate_ts));
var_dump(idate('z', $idate_ts));
var_dump(idate('W', $idate_ts));
var_dump(idate('m', $idate_ts));
var_dump(idate('n', $idate_ts));
var_dump(idate('t', $idate_ts));
var_dump(idate('L', $idate_ts));
var_dump(idate('y', $idate_ts));
var_dump(idate('Y', $idate_ts));
var_dump(idate('o', $idate_ts));
var_dump(idate('B', $idate_ts));
var_dump(idate('g', $idate_ts));
var_dump(idate('G', $idate_ts));
var_dump(idate('h', $idate_ts));
var_dump(idate('H', $idate_ts));
var_dump(idate('i', $idate_ts));
var_dump(idate('s', $idate_ts));
var_dump(idate('I', $idate_ts));
var_dump(idate('Z', $idate_ts));
var_dump(idate('U', $idate_ts));
// idate error handling
var_dump(@idate('longer_than_1', $idate_ts));
var_dump(@idate('?', $idate_ts));

// 6. getdate
date_default_timezone_set('UTC');
var_dump(getdate(1700000000));

// 7. localtime
var_dump(localtime(1700000000));
var_dump(localtime(1700000000, true));

// 8. strtotime with supported Phase A/B formats
date_default_timezone_set('Europe/Berlin');
var_dump(strtotime('@1700000000'));
var_dump(strtotime('2024-01-02'));
date_default_timezone_set('UTC');
var_dump(strtotime('@1700000000'));
var_dump(strtotime('2024-01-02'));
