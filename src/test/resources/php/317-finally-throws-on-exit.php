<?php
// A finally block that throws while running for a return or break runs once (bytecode 17b).
function ex($m) { return new Exception($m); }
function f() {
    try { return 1; } finally { echo "fin\n"; throw ex("x"); }
}
try { f(); } catch (Exception $e) { echo "caught ", $e->getMessage(), " prev=", $e->getPrevious() ? "yes" : "no", "\n"; }
function g() {
    for ($i = 0; $i < 2; $i++) {
        try { echo "t$i\n"; break; } finally { echo "fin$i\n"; if ($i == 0) throw ex("y"); }
    }
}
try { g(); } catch (Exception $e) { echo "caught ", $e->getMessage(), "\n"; }
function h() {
    try { throw ex("a"); } catch (Exception $e) { return "c"; } finally { echo "finh\n"; }
}
echo h(), "\n";
function k() { try { return "try"; } finally { return "fin"; } }
echo k(), "\n";
function m() {
    try { try { throw ex("in"); } finally { echo "f1\n"; throw ex("f1ex"); } }
    catch (Exception $e) { echo $e->getMessage(), " prev=", $e->getPrevious()?->getMessage(), "\n"; }
}
m();
