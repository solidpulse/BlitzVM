<?php
// Generators: @ around a generator call only silences the call itself, because the body runs
// on iteration. @ inside the body, and @yield, stay in effect across a suspension, as in php.
function level(string $tag): void
{
    echo "$tag er=", error_reporting(), "\n";
}

function counted(): Generator
{
    level('  gen start');
    @file_get_contents(__DIR__ . '/inc/no-such-silence-file');
    level('  gen after @');
    yield 1;
    level('  gen resumed');
    yield 2;
}

function suspended(): Generator
{
    level('  suspended start');
    $sent = @yield 10;
    level('  suspended resumed');
    yield $sent;
}

function collect(): void
{
    foreach (counted() as $value) {
        echo "  collected $value\n";
        level('  collector');
    }
}

level('start');
$g = @counted();
level('after @counted()');
foreach ($g as $value) {
    echo "value $value\n";
    level('  loop');
}
level('end');

level('start2');
$s = suspended();
foreach ($s as $key => $value) {
    echo "suspended $key => ", var_export($value, true), "\n";
    level('  suspended loop');
}
level('end2');

// Called from inside a silenced function, the generator body runs at the silenced level.
level('start3');
@collect();
level('end3');
