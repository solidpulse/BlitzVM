<?php
// stream_set_chunk_size() validates the size and reports it back; the runtime has no
// buffering layer to reconfigure.
$f = fopen('php://memory', 'w+');
var_dump(stream_set_chunk_size($f, 8192));
foreach ([0, -1] as $size) {
    try { stream_set_chunk_size($f, $size); } catch (Throwable $e) {
        echo get_class($e), ': ', $e->getMessage(), "\n";
    }
}
try { var_dump(stream_set_chunk_size('x', 1)); } catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
try { stream_set_chunk_size($f); } catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
fwrite($f, 'data');
var_dump(stream_get_contents($f, -1, 0));
