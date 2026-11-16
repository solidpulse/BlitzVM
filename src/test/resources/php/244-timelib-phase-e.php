<?php
// Conformance tests for Phase E: Intervals (DateInterval, ISO 8601 intervals, diff, add, sub)

date_default_timezone_set('UTC');

// 1. Basic ISO 8601 interval constructions
function dump_interval(DateInterval $i) {
    echo sprintf(
        "y=%d m=%d d=%d h=%d i=%d s=%d f=%.6f invert=%d days=%s\n",
        $i->y, $i->m, $i->d, $i->h, $i->i, $i->s, $i->f, $i->invert,
        var_export($i->days, true)
    );
}

dump_interval(new DateInterval('P1Y2M3DT4H5M6S'));
dump_interval(new DateInterval('P5Y'));
dump_interval(new DateInterval('P3M'));
dump_interval(new DateInterval('P10D'));
dump_interval(new DateInterval('PT2H'));
dump_interval(new DateInterval('PT15M'));
dump_interval(new DateInterval('PT30S'));
dump_interval(new DateInterval('P2W'));
dump_interval(new DateInterval('P0D'));

// 2. DateInterval::createFromDateString
dump_interval(DateInterval::createFromDateString('2 days + 3 hours'));
dump_interval(DateInterval::createFromDateString('1 year 2 months 3 days 4 hours 5 minutes 6 seconds'));
dump_interval(DateInterval::createFromDateString('1 week'));
dump_interval(DateInterval::createFromDateString('next month'));
dump_interval(DateInterval::createFromDateString('yesterday'));

// 3. DateInterval::format
$i = new DateInterval('P1Y2M3DT4H5M6S');
echo $i->format('%%Y=%Y %%y=%y %%M=%M %%m=%m %%D=%D %%d=%d %%H=%H %%h=%h %%I=%I %%i=%i %%S=%S %%s=%s %%F=%F %%f=%f %%R=%R %%r=%r %%%%'), "\n";

// 4. DateTimeImmutable diff
$dt1 = new DateTimeImmutable('2024-01-01 12:00:00.100000');
$dt2 = new DateTimeImmutable('2024-03-05 15:30:45.650000');
$diff = $dt1->diff($dt2);
dump_interval($diff);
echo $diff->format('%r%y years, %m months, %d days, %h:%i:%s.%F (total %a days)'), "\n";

$invDiff = $dt2->diff($dt1);
dump_interval($invDiff);
echo $invDiff->format('%r%y years, %m months, %d days, %h:%i:%s.%F (total %a days)'), "\n";

// Leap year diffs
$leap1 = new DateTimeImmutable('2024-02-28 00:00:00');
$leap2 = new DateTimeImmutable('2024-03-01 00:00:00');
echo $leap1->diff($leap2)->format('%d day, total %a days'), "\n";

$nonleap1 = new DateTimeImmutable('2023-02-28 00:00:00');
$nonleap2 = new DateTimeImmutable('2023-03-01 00:00:00');
echo $nonleap1->diff($nonleap2)->format('%d day, total %a days'), "\n";

// Diff across timezones
$tzUtc = new DateTimeImmutable('2024-06-01 12:00:00', new DateTimeZone('UTC'));
$tzBln = new DateTimeImmutable('2024-06-01 14:00:00', new DateTimeZone('Europe/Berlin'));
$tzDiff = $tzUtc->diff($tzBln);
echo $tzDiff->format('%h hours, %i minutes, total %a days'), "\n";

// 5. Date arithmetic (add and sub)
$base = new DateTimeImmutable('2024-01-15 10:00:00');
echo $base->add(new DateInterval('P1M5D'))->format('Y-m-d H:i:s'), "\n";
echo $base->sub(new DateInterval('P10DT2H'))->format('Y-m-d H:i:s'), "\n";

// Month overflow
$endJan = new DateTimeImmutable('2024-01-31 12:00:00');
echo $endJan->add(new DateInterval('P1M'))->format('Y-m-d H:i:s'), "\n";

// Procedural functions
$procI = date_interval_create_from_date_string('10 days');
echo date_interval_format($procI, '%d days'), "\n";
$procDt1 = date_create('2024-01-01');
$procDt2 = date_create('2024-01-11');
$procDiff = date_diff($procDt1, $procDt2);
echo date_interval_format($procDiff, '%d days, total %a days'), "\n";
date_add($procDt1, $procI);
echo date_format($procDt1, 'Y-m-d'), "\n";
date_sub($procDt1, $procI);
echo date_format($procDt1, 'Y-m-d'), "\n";

// 6. Error handling
$badIntervals = ['P', 'PT', 'P1X', 'invalid', ''];
foreach ($badIntervals as $bad) {
    try {
        new DateInterval($bad);
        echo "FAIL: Expected exception for '$bad'\n";
    } catch (DateMalformedIntervalStringException $e) {
        echo "CAUGHT: " . get_class($e) . " for '$bad'\n";
    } catch (Throwable $e) {
        echo "WRONG EXCEPTION: " . get_class($e) . " for '$bad'\n";
    }
}

try {
    DateInterval::createFromDateString('2024-01-01');
    echo "FAIL: Expected non-relative exception\n";
} catch (DateMalformedIntervalStringException $e) {
    echo "CAUGHT: " . get_class($e) . " for non-relative string\n";
}

try {
    DateInterval::createFromDateString('completely unparseable string');
    echo "FAIL: Expected unparseable exception\n";
} catch (DateMalformedIntervalStringException $e) {
    echo "CAUGHT: " . get_class($e) . " for unparseable string\n";
}
