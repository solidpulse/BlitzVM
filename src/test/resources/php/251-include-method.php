<?php
class ScopeParent {
    private $secret = 'private';
    const LABEL = 'parent';
    function view($arg) {
        echo include __DIR__ . '/inc/scope-method.php', "\n";
        echo $made(), "\n";
    }
}
class ScopeChild extends ScopeParent { const LABEL = 'child'; }
(new ScopeChild)->view('argument');
