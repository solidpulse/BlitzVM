<?php
if (false) { $scope_unused = 1; }
$scope_z = 1;
extract(['scope_a' => 2, 'scope_b' => 3]);
$scope_c = 4;
function show_scope_globals() {
    echo implode(',', array_keys(array_filter($GLOBALS, function ($v, $k) {
        return str_starts_with($k, 'scope_');
    }, ARRAY_FILTER_USE_BOTH))), "\n";
}
show_scope_globals();
unset($scope_z); $scope_z = 5;
show_scope_globals();
$vars = get_defined_vars();
echo json_encode([$vars['scope_z'], $vars['scope_a'], $vars['scope_b'], $vars['scope_c']]), "\n";
