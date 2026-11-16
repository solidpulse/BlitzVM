<?php
function leaf($value) { yield $value; return $value + 1; }
function tree() {
    foreach (new ArrayIterator([2,3]) as $key => $value) {
        $result = yield from leaf($value);
        yield "return:$result";
    }
}
echo json_encode(iterator_to_array(tree(), false)), "\n";
class BoundGenerator {
    private $value = 6;
    const LABEL = 'bound';
}
$closure = function () { yield $this->value; return self::LABEL; };
$bound = $closure->bindTo(new BoundGenerator, BoundGenerator::class);
$g = $bound();
echo json_encode(iterator_to_array($g)), ':', $g->getReturn(), "\n";
function tracedGenerator() {
    yield 'ready';
    $trace = debug_backtrace(DEBUG_BACKTRACE_IGNORE_ARGS);
    echo $trace[0]['function'], "\n";
    throw new Exception('trace');
}
$g = tracedGenerator(); $g->current();
try { $g->next(); } catch (Exception $e) { $trace = $e->getTrace(); echo $trace[0]['function'], ':', $e->getMessage(), "\n"; }
