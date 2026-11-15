<?php

interface HookFilter {
    public function apply(string $value): string;
}

#[AllowDynamicProperties]
class WP_Hook_Lite {
    public array $callbacks = [];
    private array $iterations = [];
    private array $current_priority = [];
    private int $nesting_level = 0;

    public function add_filter(string $id, mixed $fn, int $priority = 10, int $accepted_args = 1): void {
        $this->callbacks[$priority][$id] = [
            'function' => $fn,
            'accepted_args' => $accepted_args,
        ];
    }

    public function has_filter(string $id, int $priority = 10): bool {
        return isset($this->callbacks[$priority][$id]);
    }

    public function remove_filter(string $id, int $priority = 10): bool {
        if (!isset($this->callbacks[$priority][$id])) {
            return false;
        }
        unset($this->callbacks[$priority][$id]);
        if (empty($this->callbacks[$priority])) {
            unset($this->callbacks[$priority]);
        }
        return true;
    }

    public function apply_filters(string $value): string {
        if (empty($this->callbacks)) {
            return $value;
        }
        $this->nesting_level++;
        $priorities = array_keys($this->callbacks);
        sort($priorities);
        foreach ($priorities as $priority) {
            foreach ($this->callbacks[$priority] as $cb) {
                $fn = $cb['function'];
                if ($fn instanceof HookFilter) {
                    $value = $fn->apply($value);
                } elseif (is_array($fn) && is_object($fn[0])) {
                    $obj = $fn[0];
                    $method = $fn[1];
                    $value = $obj->$method($value);
                }
            }
        }
        $this->nesting_level--;
        return $value;
    }
}

class HookManager {
    private static array $hooks = [];

    public static function register(string $name): WP_Hook_Lite {
        if (!isset(self::$hooks[$name])) {
            self::$hooks[$name] = new WP_Hook_Lite();
        }
        return self::$hooks[$name];
    }
}

class UpperTransform {
    public function transform(string $v): string {
        return strtoupper($v);
    }
}

$hook = HookManager::register('the_title');
$hook->add_filter('wrap', new class('[', ']') implements HookFilter {
    private string $l;
    private string $r;
    public function __construct(string $l, string $r) {
        $this->l = $l;
        $this->r = $r;
    }
    public function apply(string $value): string {
        return $this->l . $value . $this->r;
    }
}, 20);
$hook->add_filter('upper', [new UpperTransform(), 'transform'], 5);
$hook->add_filter('trim', new class implements HookFilter {
    public function apply(string $value): string {
        return trim($value);
    }
}, 1);

echo $hook->apply_filters("  hello world  "), "\n";
var_dump($hook->has_filter('upper', 5), $hook->remove_filter('upper', 5), $hook->has_filter('upper', 5));
echo $hook->apply_filters("  hello world  "), "\n";
