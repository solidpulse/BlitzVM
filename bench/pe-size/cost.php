<?php
// Each f_<name> repeats one construct 10 times; bench/pe-size/measure.sh compiles them and
// prints machine code bytes per occurrence (tier 1, minus f_empty). See tasks/HANDOFF.md.
function ufn($a) { return $a; }
class C { public $p = 1; function m() { return 1; } static function sm() { return 2; } }
function f_empty($a, $arr, $s, $o) { $x = null;           return $x; }
function f_assign_int($a, $arr, $s, $o) { $x = null; $x = 1; $x = 1; $x = 1; $x = 1; $x = 1; $x = 1; $x = 1; $x = 1; $x = 1; $x = 1; return $x; }
function f_add($a, $arr, $s, $o) { $x = null; $x = $a + 1; $x = $a + 1; $x = $a + 1; $x = $a + 1; $x = $a + 1; $x = $a + 1; $x = $a + 1; $x = $a + 1; $x = $a + 1; $x = $a + 1; return $x; }
function f_concat($a, $arr, $s, $o) { $x = null; $s = $s . 'x'; $s = $s . 'x'; $s = $s . 'x'; $s = $s . 'x'; $s = $s . 'x'; $s = $s . 'x'; $s = $s . 'x'; $s = $s . 'x'; $s = $s . 'x'; $s = $s . 'x'; return $x; }
function f_if_cmp($a, $arr, $s, $o) { $x = null; if ($a > 5) { $x = 1; } if ($a > 5) { $x = 1; } if ($a > 5) { $x = 1; } if ($a > 5) { $x = 1; } if ($a > 5) { $x = 1; } if ($a > 5) { $x = 1; } if ($a > 5) { $x = 1; } if ($a > 5) { $x = 1; } if ($a > 5) { $x = 1; } if ($a > 5) { $x = 1; } return $x; }
function f_isset_idx($a, $arr, $s, $o) { $x = null; $x = isset($arr['k']); $x = isset($arr['k']); $x = isset($arr['k']); $x = isset($arr['k']); $x = isset($arr['k']); $x = isset($arr['k']); $x = isset($arr['k']); $x = isset($arr['k']); $x = isset($arr['k']); $x = isset($arr['k']); return $x; }
function f_idx_read($a, $arr, $s, $o) { $x = null; $x = $arr['k']; $x = $arr['k']; $x = $arr['k']; $x = $arr['k']; $x = $arr['k']; $x = $arr['k']; $x = $arr['k']; $x = $arr['k']; $x = $arr['k']; $x = $arr['k']; return $x; }
function f_idx_write($a, $arr, $s, $o) { $x = null; $arr['k'] = 1; $arr['k'] = 1; $arr['k'] = 1; $arr['k'] = 1; $arr['k'] = 1; $arr['k'] = 1; $arr['k'] = 1; $arr['k'] = 1; $arr['k'] = 1; $arr['k'] = 1; return $x; }
function f_arr_lit($a, $arr, $s, $o) { $x = null; $x = ['a', 'b']; $x = ['a', 'b']; $x = ['a', 'b']; $x = ['a', 'b']; $x = ['a', 'b']; $x = ['a', 'b']; $x = ['a', 'b']; $x = ['a', 'b']; $x = ['a', 'b']; $x = ['a', 'b']; return $x; }
function f_call_is_array($a, $arr, $s, $o) { $x = null; $x = is_array($arr); $x = is_array($arr); $x = is_array($arr); $x = is_array($arr); $x = is_array($arr); $x = is_array($arr); $x = is_array($arr); $x = is_array($arr); $x = is_array($arr); $x = is_array($arr); return $x; }
function f_call_count($a, $arr, $s, $o) { $x = null; $x = count($arr); $x = count($arr); $x = count($arr); $x = count($arr); $x = count($arr); $x = count($arr); $x = count($arr); $x = count($arr); $x = count($arr); $x = count($arr); return $x; }
function f_call_strlen($a, $arr, $s, $o) { $x = null; $x = strlen($s); $x = strlen($s); $x = strlen($s); $x = strlen($s); $x = strlen($s); $x = strlen($s); $x = strlen($s); $x = strlen($s); $x = strlen($s); $x = strlen($s); return $x; }
function f_call_user($a, $arr, $s, $o) { $x = null; $x = ufn($a); $x = ufn($a); $x = ufn($a); $x = ufn($a); $x = ufn($a); $x = ufn($a); $x = ufn($a); $x = ufn($a); $x = ufn($a); $x = ufn($a); return $x; }
function f_foreach($a, $arr, $s, $o) { $x = null; foreach ($arr as $k => $v) { $x = $v; } foreach ($arr as $k => $v) { $x = $v; } foreach ($arr as $k => $v) { $x = $v; } foreach ($arr as $k => $v) { $x = $v; } foreach ($arr as $k => $v) { $x = $v; } foreach ($arr as $k => $v) { $x = $v; } foreach ($arr as $k => $v) { $x = $v; } foreach ($arr as $k => $v) { $x = $v; } foreach ($arr as $k => $v) { $x = $v; } foreach ($arr as $k => $v) { $x = $v; } return $x; }
function f_prop_read($a, $arr, $s, $o) { $x = null; $x = $o->p; $x = $o->p; $x = $o->p; $x = $o->p; $x = $o->p; $x = $o->p; $x = $o->p; $x = $o->p; $x = $o->p; $x = $o->p; return $x; }
function f_prop_write($a, $arr, $s, $o) { $x = null; $o->p = 1; $o->p = 1; $o->p = 1; $o->p = 1; $o->p = 1; $o->p = 1; $o->p = 1; $o->p = 1; $o->p = 1; $o->p = 1; return $x; }
function f_method_call($a, $arr, $s, $o) { $x = null; $x = $o->m(); $x = $o->m(); $x = $o->m(); $x = $o->m(); $x = $o->m(); $x = $o->m(); $x = $o->m(); $x = $o->m(); $x = $o->m(); $x = $o->m(); return $x; }
function f_static_call($a, $arr, $s, $o) { $x = null; $x = C::sm(); $x = C::sm(); $x = C::sm(); $x = C::sm(); $x = C::sm(); $x = C::sm(); $x = C::sm(); $x = C::sm(); $x = C::sm(); $x = C::sm(); return $x; }
function f_coalesce($a, $arr, $s, $o) { $x = null; $x = $arr['k'] ?? 0; $x = $arr['k'] ?? 0; $x = $arr['k'] ?? 0; $x = $arr['k'] ?? 0; $x = $arr['k'] ?? 0; $x = $arr['k'] ?? 0; $x = $arr['k'] ?? 0; $x = $arr['k'] ?? 0; $x = $arr['k'] ?? 0; $x = $arr['k'] ?? 0; return $x; }
function f_is_string_or($a, $arr, $s, $o) { $x = null; $x = is_string($s) || is_int($a); $x = is_string($s) || is_int($a); $x = is_string($s) || is_int($a); $x = is_string($s) || is_int($a); $x = is_string($s) || is_int($a); $x = is_string($s) || is_int($a); $x = is_string($s) || is_int($a); $x = is_string($s) || is_int($a); $x = is_string($s) || is_int($a); $x = is_string($s) || is_int($a); return $x; }
function f_strict_eq($a, $arr, $s, $o) { $x = null; $x = 0 === $a; $x = 0 === $a; $x = 0 === $a; $x = 0 === $a; $x = 0 === $a; $x = 0 === $a; $x = 0 === $a; $x = 0 === $a; $x = 0 === $a; $x = 0 === $a; return $x; }
function f_global($a, $arr, $s, $o) { $x = null; global $g; $x = $g; global $g; $x = $g; global $g; $x = $g; global $g; $x = $g; global $g; $x = $g; global $g; $x = $g; global $g; $x = $g; global $g; $x = $g; global $g; $x = $g; global $g; $x = $g; return $x; }
function f_bare_is_array($a, $arr, $s, $o) { $x = null; is_array($arr); is_array($arr); is_array($arr); is_array($arr); is_array($arr); is_array($arr); is_array($arr); is_array($arr); is_array($arr); is_array($arr); return $x; }
function f_assign_param($a, $arr, $s, $o) { $x = null; $x = $s; $x = $s; $x = $s; $x = $s; $x = $s; $x = $s; $x = $s; $x = $s; $x = $s; $x = $s; return $x; }
function f_assign_param_arr($a, $arr, $s, $o) { $x = null; $x = $arr; $x = $arr; $x = $arr; $x = $arr; $x = $arr; $x = $arr; $x = $arr; $x = $arr; $x = $arr; $x = $arr; return $x; }
function f_if_is_array($a, $arr, $s, $o) { $x = null; if (is_array($arr)) { $a = 1; } if (is_array($arr)) { $a = 1; } if (is_array($arr)) { $a = 1; } if (is_array($arr)) { $a = 1; } if (is_array($arr)) { $a = 1; } if (is_array($arr)) { $a = 1; } if (is_array($arr)) { $a = 1; } if (is_array($arr)) { $a = 1; } if (is_array($arr)) { $a = 1; } if (is_array($arr)) { $a = 1; } return $x; }
$o = new C; $arr = ['k' => 1, 'z' => [1,2]]; $g = 3;
for ($i = 0; $i < 200000; $i++) {
  f_if_is_array($i, $arr, 'str', $o);
  f_assign_param_arr($i, $arr, 'str', $o);
  f_assign_param($i, $arr, 'str', $o);
  f_bare_is_array($i, $arr, 'str', $o);
  f_empty($i, $arr, 'str', $o);
  f_assign_int($i, $arr, 'str', $o);
  f_add($i, $arr, 'str', $o);
  f_concat($i, $arr, 'str', $o);
  f_if_cmp($i, $arr, 'str', $o);
  f_isset_idx($i, $arr, 'str', $o);
  f_idx_read($i, $arr, 'str', $o);
  f_idx_write($i, $arr, 'str', $o);
  f_arr_lit($i, $arr, 'str', $o);
  f_call_is_array($i, $arr, 'str', $o);
  f_call_count($i, $arr, 'str', $o);
  f_call_strlen($i, $arr, 'str', $o);
  f_call_user($i, $arr, 'str', $o);
  f_foreach($i, $arr, 'str', $o);
  f_prop_read($i, $arr, 'str', $o);
  f_prop_write($i, $arr, 'str', $o);
  f_method_call($i, $arr, 'str', $o);
  f_static_call($i, $arr, 'str', $o);
  f_coalesce($i, $arr, 'str', $o);
  f_is_string_or($i, $arr, 'str', $o);
  f_strict_eq($i, $arr, 'str', $o);
  f_global($i, $arr, 'str', $o);
}
