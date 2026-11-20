<?php
function request_memo() {
    static $memo = null;
    if ($memo === null) {
        $memo = isset($_GET['x']) ? $_GET['x'] : '';
    }
    return $memo;
}

request_memo();
