<?php
function validation() {
    foreach ([7, -1, 512] as $f) {
        try { extract([], $f); } catch (ValueError $e) { echo $e->getMessage(), "\n"; }
    }
    try { extract(['a' => 1], EXTR_PREFIX_ALL); } catch (ValueError $e) { echo $e->getMessage(), "\n"; }
    try { extract([], EXTR_PREFIX_SAME, 'bad-x'); } catch (ValueError $e) { echo $e->getMessage(), "\n"; }
    try { extract(['this' => 1]); } catch (Error $e) { echo $e->getMessage(), "\n"; }
    echo extract(['this' => 1], EXTR_IF_EXISTS), "\n";
    echo extract(['_GET' => ['local' => 1]]), ":", count($_GET), "\n";
    try { extract([], []); } catch (TypeError $e) { echo $e->getMessage(), "\n"; }
    try { extract([], EXTR_PREFIX_ALL, []); } catch (TypeError $e) { echo $e->getMessage(), "\n"; }
}
validation();
