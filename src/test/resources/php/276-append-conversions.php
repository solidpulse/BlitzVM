<?php
function conversion($value) { $s = $value; $s .= 'x'; $s .= '!'; var_dump($s); }
conversion(12); conversion(null); conversion(1.25); conversion(true); conversion(false);
class AppendText {
    function __toString() { return 'object'; }
}
conversion(new AppendText());
function object_suffix() { $s = ''; $s .= new AppendText(); echo $s, "\n"; }
object_suffix();
class MutatingText {
    private $output;
    function __construct(&$output) { $this->output =& $output; }
    function __toString() { $this->output .= 'inside'; return '-text'; }
}
function reentrant_text() { $s = ''; $s .= 'before-'; $s .= new MutatingText($s); echo $s, "\n"; }
reentrant_text();
