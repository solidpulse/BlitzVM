<?php
// Conformance tests for Phase D: timelib_parse_from_format and date_parse_from_format

date_default_timezone_set('UTC');

// 1. Basic formats
var_dump(date_parse_from_format('Y-m-d H:i:s', '2024-05-15 14:30:45'));
var_dump(date_parse_from_format('d.m.Y H:i:s.u', '15.05.2024 14:30:45.123456'));
var_dump(date_parse_from_format('j/n/y G:i:s.v', '5/3/24 9:05:07.789'));

// 2. Resets: ! (reset all) and | (reset unset)
var_dump(date_parse_from_format('!Y-m-d', '2024-01-02'));
var_dump(date_parse_from_format('Y-m-d|', '2024-01-02'));
var_dump(date_parse_from_format('!H:i:s', '12:34:56'));

// 3. Modifiers: + (allow extra characters)
var_dump(date_parse_from_format('+Y-m-d', '2024-01-02 extra stuff'));
var_dump(date_parse_from_format('Y-m-d', '2024-01-02 extra stuff')); // trailing error without +

// 4. Escaping
var_dump(date_parse_from_format('\Y\e\a\r: Y', 'Year: 2024'));

// 5. Wildcards and separators: ? (random char), # (any separator), * (skip to separator)
var_dump(date_parse_from_format('Y?m?d', '2024X05Y15'));
var_dump(date_parse_from_format('Y#m#d', '2024/05-15'));
var_dump(date_parse_from_format('Y*d', '2024 random words here -15'));

// 6. Textual months and days
var_dump(date_parse_from_format('D, d M Y', 'Wed, 15 May 2024'));
var_dump(date_parse_from_format('l, d F Y', 'Wednesday, 15 May 2024'));

// 7. 12-hour format and meridians
var_dump(date_parse_from_format('g:i:s a', '2:30:15 pm'));
var_dump(date_parse_from_format('h:i:s A', '12:00:00 AM'));
var_dump(date_parse_from_format('h:i:s a', '12:00:00 pm'));
var_dump(date_parse_from_format('h a', '13 pm')); // error: hour > 12

// 8. Day of year (z) and Day suffix (S)
var_dump(date_parse_from_format('Y-z', '2024-135'));
var_dump(date_parse_from_format('jS \o\f F Y', '15th of May 2024'));

// 9. Epoch seconds (U)
var_dump(date_parse_from_format('U', '1715774400'));
var_dump(date_parse_from_format('U u', '1715774400 123456'));

// 10. Timezones: offset, abbr, identifier
var_dump(date_parse_from_format('Y-m-d H:i:s P', '2024-05-15 14:30:00 +02:00'));
var_dump(date_parse_from_format('Y-m-d H:i:s O', '2024-05-15 14:30:00 -0400'));
var_dump(date_parse_from_format('Y-m-d H:i:s T', '2024-05-15 14:30:00 CEST'));
var_dump(date_parse_from_format('Y-m-d H:i:s e', '2024-05-15 14:30:00 Europe/Berlin'));

// 11. Expanded years (x, X)
var_dump(date_parse_from_format('X-m-d', '+2024-05-15'));
var_dump(date_parse_from_format('x-m-d', '-0044-03-15'));

// 12. Error cases
var_dump(date_parse_from_format('Y-m-d', '2024-05')); // not enough data
var_dump(date_parse_from_format('Y-m-d', '2024/05/15')); // format separator does not match
var_dump(date_parse_from_format('a H', 'pm 14')); // meridian before hour
var_dump(date_parse_from_format('Y-m-d H:i:s', '2024-02-30 25:61:61')); // invalid date and time warnings
