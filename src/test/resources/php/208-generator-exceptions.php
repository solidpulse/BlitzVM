<?php
function guarded() {
    try {
        yield 'ready';
        throw new RuntimeException('inside');
    } catch (RuntimeException $e) {
        echo 'caught:', $e->getMessage(), '|';
        yield 'recovered';
    } finally {
        echo 'finally|';
        yield 'cleanup';
        echo 'cleaned|';
    }
    return 'finished';
}
$g = guarded();
echo $g->current(), '|';
$g->next(); echo $g->current(), '|';
$g->next(); echo $g->current(), '|';
$g->next(); echo $g->getReturn(), "\n";
$g = guarded();
echo $g->current(), '|', $g->throw(new RuntimeException('injected')), '|';
$g->next(); echo $g->current(), '|';
$g->next(); echo $g->getReturn(), "\n";
function fail() { try { yield 1; throw new Exception('boom'); } finally { echo 'fail-finally|'; } }
$g = fail(); $g->current();
try { $g->next(); } catch (Exception $e) { echo $e->getMessage(), '|'; }
var_dump($g->valid());
function finishFinally() { try { yield 1; return 7; } finally { yield 2; echo 'end|'; } }
$g = finishFinally(); echo json_encode(iterator_to_array($g)), ':', $g->getReturn(), "\n";
