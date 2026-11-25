<?php
function run_include(): void {
    $local = 'local';
    $marker = include __DIR__ . '/inc/760-context-helper.php';
    $box = new IncludedBox('box');
    echo "scope:$local:$marker:", $box->label(), "\n";
}
run_include();
$marker = include_once __DIR__ . '/inc/760-context-helper.php';
$box = new IncludedBox('global');
echo "global:", var_export($marker, true), ":", $box->label(), "\n";
