<?php
// @ nesting: the level of the inner @ is the masked outer level, and the outer restore
// still happens once. An exception out of the operand, or out of a called function, always
// restores it.
function level(string $tag): void
{
    echo "$tag er=", error_reporting(), "\n";
}

function silenced_inner(): void
{
    level('    inner start');
    @$undefinedInFunction;
    level('    inner after @');
    @level('    inner nested');
    level('    inner end');
}

function throwing(): void
{
    level('    thrower');
    throw new RuntimeException('boom');
}

function outer_with_catch(): void
{
    level('  outer start');
    try {
        @throwing();
    } catch (RuntimeException $e) {
        level('  outer catch');
        echo "  caught ", $e->getMessage(), "\n";
    }
    level('  outer after catch');
}

class Thrower
{
    public function method(): void
    {
        level('    thrower method');
        throw new LogicException('method boom');
    }

    public static function staticMethod(): void
    {
        level('    thrower static');
        throw new LogicException('static boom');
    }
}

level('start');
@silenced_inner();
level('after @function');

@ @ silenced_inner();
level('after @@function');

try {
    @throwing();
} catch (RuntimeException $e) {
    level('catch');
    echo "caught ", $e->getMessage(), "\n";
}
level('after catch');

@outer_with_catch();
level('after @outer_with_catch');

$object = new Thrower();
try {
    @$object->method();
} catch (LogicException $e) {
    level('catch method');
}
try {
    @Thrower::staticMethod();
} catch (LogicException $e) {
    level('catch static');
}
$closure = function (): void {
    level('    thrower closure');
    throw new LogicException('closure boom');
};
try {
    @$closure();
} catch (LogicException $e) {
    level('catch closure');
}
level('end');
