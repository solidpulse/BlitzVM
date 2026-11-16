<?php
// Runs every case of a corpus file and prints one line per case: "<line>\t<kind>\t<result>".
// The same script runs on php and on BlitzVM; run.sh diffs the two. Usage:
//   CORPUS=corpus.tsv [KIND=strtotime] php runner.php
// (environment variables because BlitzVM's CLI does not set $argv yet)
error_reporting(0);
$only = getenv('KIND') ?: null;
$lines = file(getenv('CORPUS'), FILE_IGNORE_NEW_LINES);
$fmt = 'Y-m-d H:i:s.u T P e U';

function out($v) {
    if ($v === false) return 'false';
    if ($v === null) return 'null';
    return json_encode($v);
}

foreach ($lines as $n => $line) {
    $f = explode("\t", $line);
    $kind = $f[0];
    if ($only !== null && $kind !== $only) continue;
    $tz = $f[1]; $a = $f[2]; $b = isset($f[3]) ? $f[3] : '';
    try {
        date_default_timezone_set($tz);
        switch ($kind) {
            case 'date_parse':
                $r = out(date_parse($a)); break;
            case 'strtotime':
                $r = out(strtotime($a, (int) $b)); break;
            case 'create':
                $r = out((new DateTime($a))->format($fmt)); break;
            case 'modify':
                $d = new DateTime($a);
                $m = $d->modify($b);
                $r = $m === false ? 'false' : out($m->format($fmt)); break;
            case 'from_format':
                $r = out(date_parse_from_format($a, $b)); break;
            case 'create_from_format':
                $d = DateTime::createFromFormat($a, $b);
                $r = $d === false ? 'false' : out($d->format($fmt)); break;
            case 'diff':
                $r = out((new DateTime($a))->diff(new DateTime($b))->format('%R %y %m %d %h %i %s %f %a')); break;
            case 'date':
                $r = out(date($a, (int) $b)); break;
            case 'interval':
                $i = new DateInterval($a);
                $r = out($i->format('%R %y %m %d %h %i %s %f %a')); break;
            case 'zone':
                $z = new DateTimeZone($a);
                $r = out($z->getName() . ' ' . $z->getOffset(new DateTime('@1700000000'))); break;
            default:
                $r = 'unknown kind';
        }
    } catch (Throwable $e) {
        $r = get_class($e) . ': ' . $e->getMessage();
    }
    echo ($n + 1), "\t", $kind, "\t", $r, "\n";
}
