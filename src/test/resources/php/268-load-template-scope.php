<?php
$posts = 'posts'; $s = 'search';
function load_scope_template($file, $args) {
    global $posts, $s;
    $existing = 'keep';
    $query_vars = ['query' => 'query', 'existing' => 'replace', 's' => 'overwrite'];
    extract($query_vars, EXTR_SKIP);
    require $file;
    echo "$template_set:$s\n";
}
load_scope_template(__DIR__ . '/inc/scope-template.php', ['tag' => 'args']);
