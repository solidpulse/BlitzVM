<?php
// What a silenced expression does to the level itself. php restores the saved level unless
// the operand set a level with anything outside the fatal mask; ini_get is never masked.
function show(string $tag): void
{
    echo "$tag er=", error_reporting(), " ini=", ini_get('error_reporting'), "\n";
}

function set_er(int $level): void
{
    $old = error_reporting($level);
    echo "  error_reporting($level) returned $old, now er=", error_reporting(), " ini=", ini_get('error_reporting'), "\n";
}

function set_ini(string $level): void
{
    $old = ini_set('error_reporting', $level);
    echo "  ini_set('$level') returned '", $old, "', now er=", error_reporting(), " ini=", ini_get('error_reporting'), "\n";
}

function set_to_masked(): void
{
    $old = error_reporting(4437);
    echo "  error_reporting(4437) returned $old, now er=", error_reporting(), " ini=", ini_get('error_reporting'), "\n";
}

function two_sets(int $first, int $second): void
{
    error_reporting($first);
    error_reporting($second);
    echo "  after two sets er=", error_reporting(), " ini=", ini_get('error_reporting'), "\n";
}

error_reporting(22527);
show('start');

// A level with only fatal bits cannot outlive the @.
@set_er(1);
show('after @set_er(1)');

// A level with any non-fatal bit survives it.
@set_er(8);
show('after @set_er(8)');

error_reporting(22527);
@set_ini('2');
show('after @set_ini(2)');

error_reporting(22527);
@set_ini('0');
show('after @set_ini(0)');

// ini_get inside @ reports the unsilenced value even though error_reporting() is masked.
ini_set('error_reporting', '22527');
@show('inside @');
show('after @show');

// php skips the ini update when the level would not change, so the entry keeps 22527.
ini_set('error_reporting', '22527');
@set_to_masked();
show('after @set_to_masked');

// The final level inside @ decides: fatal bits after non-fatal ones restore, the reverse sticks.
error_reporting(22527);
@two_sets(8, 1);
show('after 8 then 1');
error_reporting(22527);
@two_sets(1, 8);
show('after 1 then 8');
