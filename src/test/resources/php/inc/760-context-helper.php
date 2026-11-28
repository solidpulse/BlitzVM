<?php
function helper_name(): string { return 'helper'; }
final class IncludedBox {
    public function __construct(public string $value) {}
    public function label(): string { return $this->value . ':' . helper_name(); }
}
return 'loaded';
