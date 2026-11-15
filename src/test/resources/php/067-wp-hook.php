<?php

class WpHookClosures {
    public array $callbacks = [];

    public function add_filter(int $priority, $callback, int $accepted_args = 1): void {
        $this->callbacks[$priority][] = [
            'function' => $callback,
            'accepted_args' => $accepted_args,
        ];
        ksort($this->callbacks);
    }

    public function apply_filters($value, ...$extra) {
        $all = func_get_args();
        foreach ($this->callbacks as $priority => $group) {
            foreach ($group as $idx => $entry) {
                $all[0] = $value;
                $pass = array_slice($all, 0, $entry['accepted_args']);
                $value = call_user_func_array($this->callbacks[$priority][$idx]['function'], $pass);
            }
        }
        return $value;
    }
}

$wp_filters = [];

function add_filter(string $tag, $callback, int $priority = 10, int $accepted_args = 1): void {
    global $wp_filters;
    if (!isset($wp_filters[$tag])) {
        $wp_filters[$tag] = new WpHookClosures();
    }
    $wp_filters[$tag]->add_filter($priority, $callback, $accepted_args);
}

function apply_filters(string $tag, $value) {
    global $wp_filters;
    if (!isset($wp_filters[$tag])) {
        return $value;
    }
    return call_user_func_array([$wp_filters[$tag], 'apply_filters'], array_slice(func_get_args(), 1));
}

function do_action(string $tag): void {
    global $wp_filters;
    if (!isset($wp_filters[$tag])) {
        return;
    }
    call_user_func_array([$wp_filters[$tag], 'apply_filters'], array_merge([null], array_slice(func_get_args(), 1)));
}

class FilterHelper {
    public string $suffix;
    public function __construct(string $s) { $this->suffix = $s; }
    public function appendSuffix($v, $ctx) { return $v . $this->suffix . '(' . $ctx . ')'; }
    public static function trimUpper($v) { return strtoupper(trim($v)); }
}

$helper = new FilterHelper('!');
add_filter('the_title', [$helper, 'appendSuffix'], 20, 2);
add_filter('the_title', ['FilterHelper', 'trimUpper'], 10, 1);
add_filter('the_title', fn($v) => '[' . $v . ']', 15, 1);
add_filter('the_title', 'strrev', 30, 1);

echo apply_filters('the_title', '  hello world  ', 'prod'), "\n";

$events = new stdClass();
$events->log = [];
add_filter('init', function ($ignored, $stage) use ($events) {
    $events->log[] = 'first:' . $stage;
    return $ignored;
}, 5, 2);
add_filter('init', function ($ignored, $stage) use ($events) {
    $events->log[] = 'second:' . strtoupper($stage);
    return $ignored;
}, 10, 2);
do_action('init', 'boot');
echo implode(',', $events->log), "\n";
