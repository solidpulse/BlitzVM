<?php
function suffix(): string { return '!'; }
final class S {
    public function __construct(private string $v) {}
    public function __toString(): string { return $this->v . suffix(); }
}
echo strlen(new S('ab')), "\n";
echo strtoupper(new S('cd')), "\n";
echo sprintf('[%s]', new S('ef')), "\n";
$s = 'x' . new S('gh');
echo $s, "\n";
