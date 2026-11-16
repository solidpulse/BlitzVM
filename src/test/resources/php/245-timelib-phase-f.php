<?php
// Conformance tests for Phase F: DateTime, DateTimeImmutable, DateTimeZone, DatePeriod, procedural functions, and exceptions

date_default_timezone_set('UTC');

// 1. DateTimeInterface constants
$constants = [
    DateTimeInterface::ATOM,
    DateTimeInterface::COOKIE,
    DateTimeInterface::ISO8601,
    DateTimeInterface::ISO8601_EXPANDED,
    DateTimeInterface::RFC822,
    DateTimeInterface::RFC850,
    DateTimeInterface::RFC1036,
    DateTimeInterface::RFC1123,
    DateTimeInterface::RFC7231,
    DateTimeInterface::RFC2822,
    DateTimeInterface::RFC3339,
    DateTimeInterface::RFC3339_EXTENDED,
    DateTimeInterface::RSS,
    DateTimeInterface::W3C
];
foreach ($constants as $c) {
    echo $c, "\n";
}

// 2. DateTime and DateTimeImmutable basic methods
$d = new DateTime('2024-05-15 14:30:45');
echo $d->format(DateTimeInterface::ATOM), "\n";
echo $d->format(DateTimeInterface::RFC2822), "\n";

$d->setDate(2025, 6, 20);
$d->setTime(10, 20, 30, 123456);
echo $d->format('Y-m-d H:i:s.u'), "\n";
echo "microsecond=" . $d->getMicrosecond() . "\n";
$d->setMicrosecond(654321);
echo "microsecond=" . $d->getMicrosecond() . "\n";

$d->setISODate(2025, 1, 1);
echo $d->format('Y-m-d'), "\n";

$d->setTimestamp(1704067200);
echo $d->format('Y-m-d H:i:s'), " ts=" . $d->getTimestamp() . " offset=" . $d->getOffset() . "\n";

// Modifiers
$d->modify('+1 day');
echo $d->format('Y-m-d'), "\n";
$d->modify('+1 month');
echo $d->format('Y-m-d'), "\n";
$d->modify('first day of this month');
echo $d->format('Y-m-d'), "\n";
$d->modify('last day of this month');
echo $d->format('Y-m-d'), "\n";

// DateTimeImmutable
$imm = new DateTimeImmutable('2024-01-01 12:00:00');
$immModified = $imm->modify('+2 days');
echo "orig=" . $imm->format('Y-m-d') . " mod=" . $immModified->format('Y-m-d') . "\n";

// Conversions
$fromMut = DateTimeImmutable::createFromMutable($d);
echo get_class($fromMut), " ", $fromMut->format('Y-m-d H:i:s'), "\n";
$fromImm = DateTime::createFromImmutable($imm);
echo get_class($fromImm), " ", $fromImm->format('Y-m-d H:i:s'), "\n";
$fromIntf = DateTimeImmutable::createFromInterface($d);
echo get_class($fromIntf), " ", $fromIntf->format('Y-m-d H:i:s'), "\n";

// Timezone manipulation
$tzBln = new DateTimeZone('Europe/Berlin');
$immTz = $imm->setTimezone($tzBln);
echo $immTz->format('Y-m-d H:i:s P') . " tz=" . $immTz->getTimezone()->getName() . " offset=" . $immTz->getOffset() . "\n";

// WordPress offset style timezone
$tzWp = new DateTimeZone('+05:30');
$immWp = $imm->setTimezone($tzWp);
echo $immWp->format('Y-m-d H:i:s P') . " tz=" . $immWp->getTimezone()->getName() . " offset=" . $immWp->getOffset() . "\n";

// createFromFormat and getLastErrors
$parsed = DateTimeImmutable::createFromFormat('Y-m-d H:i:s', '2024-05-15 14:30:45');
echo $parsed->format('c') . "\n";
var_dump(DateTimeImmutable::getLastErrors());

$failed = DateTimeImmutable::createFromFormat('Y-m-d', '2024-05');
var_dump($failed);
$lastErr = DateTimeImmutable::getLastErrors();
echo "err count=" . $lastErr['error_count'] . " msg=" . reset($lastErr['errors']) . "\n";

// 3. DateTimeZone methods
$tzLoc = $tzBln->getLocation();
echo sprintf("%s: lat=%.2f lon=%.2f comments=%s\n", $tzLoc['country_code'], $tzLoc['latitude'], $tzLoc['longitude'], $tzLoc['comments']);

$transitions = $tzBln->getTransitions(1704067200, 1735689600);
echo "transitions count=" . count($transitions) . "\n";
foreach ($transitions as $tr) {
    echo sprintf("ts=%d time=%s offset=%d isdst=%s abbr=%s\n", $tr['ts'], $tr['time'], $tr['offset'], var_export($tr['isdst'], true), $tr['abbr']);
}

$abbrs = DateTimeZone::listAbbreviations();
echo "has acst=" . var_export(isset($abbrs['acst']), true) . " acst offset=" . $abbrs['acst'][0]['offset'] . "\n";

$europeIds = DateTimeZone::listIdentifiers(DateTimeZone::EUROPE);
echo "europe has berlin=" . var_export(in_array('Europe/Berlin', $europeIds), true) . " gt_50=" . var_export(count($europeIds) > 50, true) . "\n";

// 4. DatePeriod
$p1 = new DatePeriod(new DateTimeImmutable('2024-01-01'), new DateInterval('P1D'), 3);
echo "p1 start=" . $p1->getStartDate()->format('Y-m-d') . " rec=" . $p1->getRecurrences() . " int=" . $p1->getDateInterval()->format('%d days') . "\n";

$p2 = new DatePeriod(new DateTimeImmutable('2024-01-01'), new DateInterval('P1D'), new DateTimeImmutable('2024-01-05'));
echo "p2 start=" . $p2->getStartDate()->format('Y-m-d') . " end=" . $p2->getEndDate()->format('Y-m-d') . " rec=" . var_export($p2->getRecurrences(), true) . "\n";

$pIso = new DatePeriod('R3/2024-01-01T00:00:00Z/P1D');
echo "pIso start=" . $pIso->getStartDate()->format('Y-m-d') . " rec=" . $pIso->getRecurrences() . "\n";

// 5. Procedural functions
$procDt = date_create('2024-05-15 14:30:45');
echo date_format($procDt, 'Y-m-d H:i:s'), "\n";
date_date_set($procDt, 2026, 1, 2);
date_time_set($procDt, 8, 9, 10, 111222);
echo date_format($procDt, 'Y-m-d H:i:s.u'), "\n";
date_isodate_set($procDt, 2026, 2, 3);
echo date_format($procDt, 'Y-m-d'), "\n";
date_timestamp_set($procDt, 1704067200);
echo date_timestamp_get($procDt), "\n";
echo date_offset_get($procDt), "\n";
echo timezone_name_get(date_timezone_get($procDt)), "\n";

$procTz = timezone_open('Europe/Berlin');
date_timezone_set($procDt, $procTz);
echo date_format($procDt, 'Y-m-d H:i:s P'), "\n";
echo timezone_offset_get($procTz, $procDt), "\n";

$procFromFmt = date_create_from_format('!Y-m-d', '2024-07-04');
echo date_format($procFromFmt, 'Y-m-d H:i:s'), "\n";
$procImmFromFmt = date_create_immutable_from_format('!Y-m-d', '2024-07-04');
echo get_class($procImmFromFmt), " ", date_format($procImmFromFmt, 'Y-m-d H:i:s'), "\n";

$procTr = timezone_transitions_get($procTz, 1704067200, 1735689600);
echo "proc transitions count=" . count($procTr) . "\n";
$procLoc = timezone_location_get($procTz);
echo sprintf("proc loc: %s lat=%.2f lon=%.2f\n", $procLoc['country_code'], $procLoc['latitude'], $procLoc['longitude']);
$procAbbrs = timezone_abbreviations_list();
echo "proc abbrs has utc=" . var_export(isset($procAbbrs['utc']), true) . "\n";
$procIds = timezone_identifiers_list(DateTimeZone::UTC);
echo "proc utc count=" . count($procIds) . "\n";

// WordPress shapes
$mysqlTs = strtotime('2024-05-15 14:30:45');
$nextDay = strtotime('+1 day', $mysqlTs);
echo "mysql=" . date('Y-m-d H:i:s', $mysqlTs) . " next=" . date('Y-m-d H:i:s', $nextDay) . "\n";

// 6. Exceptions
$exceptionTests = [
    ['DateTime', 'invalid datetime', DateMalformedStringException::class],
    ['DateTimeZone', 'Invalid/Timezone', DateInvalidTimeZoneException::class],
    ['DateInterval', 'invalid interval', DateMalformedIntervalStringException::class],
    ['DatePeriod', 'invalid period', DateMalformedPeriodStringException::class]
];

foreach ($exceptionTests as $test) {
    $cls = $test[0];
    $arg = $test[1];
    $expectedExc = $test[2];
    try {
        new $cls($arg);
        echo "FAIL: Expected exception for $cls($arg)\n";
    } catch (Throwable $e) {
        if ($e instanceof $expectedExc) {
            echo "OK: Caught expected " . get_class($e) . " for $cls\n";
        } else {
            echo "FAIL: Caught " . get_class($e) . " but expected $expectedExc for $cls\n";
        }
    }
}

try {
    $d = new DateTime();
    $d->modify('invalid modifier');
    echo "FAIL: Expected exception on modify\n";
} catch (DateMalformedStringException $e) {
    echo "OK: Caught DateMalformedStringException on modify\n";
}
