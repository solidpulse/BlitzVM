<?php
class HookRunner {
    private array $filters = [];

    public function add(string $tag, callable $cb): void {
        $this->filters[$tag][] = $cb;
    }

    public function applyRefArray(string $tag, array $args): void {
        foreach ($this->filters[$tag] ?? [] as $cb) {
            call_user_func_array($cb, $args);
        }
    }
}

class Plugin {
    public function __construct(public readonly string $prefix) {}

    public function register(HookRunner $runner): void {
        $runner->add('init', array(&$this, 'onInit'));
    }

    public function onInit(object $ctx, array &$log, int &$count): void {
        $count++;
        $log[] = "{$this->prefix}:{$ctx->site}:#$count";
    }
}

$GLOBALS['site_config'] = ['site' => 'blitz'];
$runner = new HookRunner();
$p1 = new Plugin('auth');
$p2 = new Plugin('cache');
$p1->register($runner);
$p2->register($runner);

$ctx = (object) $GLOBALS['site_config'];
$log = [];
$count = 0;
$runner->applyRefArray('init', [$ctx, &$log, &$count]);

[$firstLog, $secondLog] = $log;
echo "count=$count|$firstLog|$secondLog
";

function wp_parse_like(array $pairs, &$output): void {
    $output = [];
    foreach ($pairs as [$k, $v]) {
        $output[$k] = $v;
    }
}
wp_parse_like([['page', '2'], ['sort', 'asc']], $parsed);
$merged = [...['base' => 'home'], ...$parsed];
echo json_encode($merged) . "
";
