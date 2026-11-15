<?php

interface Describable {
    public const KIND = 'describable';
    public function describe(): string;
}

class IncludedBase {
    protected string $tag = 'base';
    public function getTag(): string {
        return $this->tag;
    }
}
