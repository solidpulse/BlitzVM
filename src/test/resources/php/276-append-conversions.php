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
class OrderedText {
    private $name;
    function __construct($name) { $this->name = $name; }
    function __toString() { echo $this->name; return $this->name; }
}
function ordered_text() { $s = new OrderedText('L'); $s .= new OrderedText('R'); echo '|', $s, "\n"; }
ordered_text();
function scalar_suffixes() { $s = ''; for ($i = 0; $i < 100; $i++) { $s .= 12; } echo strlen($s), '|', substr($s, -4), "\n"; }
scalar_suffixes();
