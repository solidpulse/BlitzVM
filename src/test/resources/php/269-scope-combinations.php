<?php
function combinations(&$input) {
    extract($input, EXTR_REFS | EXTR_PREFIX_ALL, 'p');
    $n = 'p_a'; $$n += 2;
    eval('$new_dynamic = 7;');
    echo json_encode(compact('p_a', ['new_dynamic', ['p_b']])), "\n";
    echo implode(',', array_keys(get_defined_vars())), "\n";
    $array = [1, 2];
    foreach ($array as &$p_b) { $p_b++; }
    echo json_encode($input), ':', json_encode($array), "\n";
}
$input = ['a' => 3, 'b' => 4]; combinations($input);
echo json_encode($input), "\n";
function mixed_rebind() {
    $a = 1; $b = 2;
    include __DIR__ . '/inc/scope-binding.php';
    echo "$a:$b:$file_value\n";
}
mixed_rebind();
eval('namespace ScopeNs; function run($a) { extract(["b" => 2]); echo json_encode(compact("a", "b")), "\\n"; echo implode(",", array_keys(get_defined_vars())), "\\n"; } run(1);');
eval('namespace ScopeOverride; function compact($name) { return ["override" => $name]; } function run() { echo json_encode(compact("a")), "\\n"; } run();');
