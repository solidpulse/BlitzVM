<?php
declare(strict_types=1);

// Closures declared in a strict file. The declaring file's strict_types applies to their
// return checks; array_map's internal callback invocation is still weak for parameters.
function strictIntClosure()
{
    return function (int $x) {
        return $x + 1;
    };
}

function strictReturnClosure()
{
    return function (): int {
        return "7";
    };
}

function strictMapInsideStrictFile()
{
    return array_map(fn (int $x) => $x + 1, ["8"]);
}
