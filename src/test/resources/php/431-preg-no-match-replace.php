<?php
// preg_replace and preg_replace_callback when nothing matches: the subject comes back
// unchanged, $count is 0, and a callback is not invoked. Also arrays, limits and the
// optional by-reference $count argument.

function show($label, $value) {
    echo $label, ': ';
    var_export($value);
    echo "\n";
}

$subject = "The quick brown fox jumps over the lazy dog.\n";
show('no match', preg_replace('/zzz/', 'X', $subject));
show('no match count', preg_replace('/zzz/', 'X', $subject, -1, $count));
show('count after no match', $count);

$count = 99;
show('replaced', preg_replace('/o/', '0', $subject, -1, $count));
show('count after replace', $count);

foreach ([0, 1, 2, -1] as $limit) {
    $c = null;
    $r = preg_replace('/o/', '0', $subject, $limit, $c);
    show("limit $limit", [$r, $c]);
}

// An array of patterns and an array of replacements, no match at all.
$patterns = ['/aaa/', '/bbb/'];
$replacements = ['1', '2'];
$subjects = ['alpha' => 'one', 'beta' => 'two'];
show('arrays no match', preg_replace($patterns, $replacements, $subjects));

// One pattern matches, the other does not; $count is the total.
$c = 0;
show('arrays mixed', preg_replace(['/e/', '/zzz/'], ['E', 'X'], 'one two three', -1, $c));
show('arrays mixed count', $c);

// A single pattern with an array of subjects.
show('subject array', preg_replace('/o/', '0', ['no' => 'foo', 'empty' => '', 7 => 'bar']));

// preg_replace_callback must not call the callback when there is no match.
$calls = 0;
$r = preg_replace_callback('/zzz/', function ($m) use (&$calls) { $calls++; return 'X'; }, $subject, -1, $c);
show('callback no match', $r);
show('callback calls', $calls);
show('callback count', $c);

$calls = 0;
$c = 0;
$r = preg_replace_callback('/(\w+) (\w+)/', function ($m) use (&$calls) { $calls++; return strtoupper($m[2]) . ' ' . strtoupper($m[1]); }, 'one two three four', 1, $c);
show('callback limit 1', $r);
show('callback limit calls', $calls);
show('callback limit count', $c);

// preg_replace_callback_array with no matching pattern.
$calls = 0;
$r = preg_replace_callback_array([
    '/zzz/' => function ($m) use (&$calls) { $calls++; return 'X'; },
], $subject, -1, $c);
show('callback array no match', $r);
show('callback array calls', $calls);
show('callback array count', $c);

// str_replace shares the optional by-reference $count path.
show('str_replace no count', str_replace('fox', 'cat', $subject));
$count = 0;
show('str_replace count', str_replace('o', '0', $subject, $count));
show('str_replace count value', $count);
