<?php
// Included by the 400/401 fixtures: a file without strict_types.
function weakParam(int $x) { return $x; }
function weakReturn(): int { return "7"; }
function weakPropertyHolder(): TypedHolder { return new TypedHolder(); }
class TypedHolder {
    public int $number;
}
