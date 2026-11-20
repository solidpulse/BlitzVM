<?php
declare(strict_types=1);

// Included by the 400/401 fixtures: a file with strict_types.
function strictParam(int $x) { return $x; }
function strictReturn(): int { return "7"; }
function strictPropertyHolder(): StrictTypedHolder { return new StrictTypedHolder(); }
class StrictTypedHolder {
    public int $number;
}
