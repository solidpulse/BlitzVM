<?php
parse_str('text=%C3%A9&raw=%FF&nul=%00&nested[k]=%F0%9F%98%80', $a);
foreach ($a as $k => $v) {
    echo $k, ':', is_array($v) ? bin2hex($v['k']) : bin2hex($v), "\n";
}
echo http_build_query($a), "\n";
echo bin2hex("\xFF\x80" & "\xC3\xA9"), ':', bin2hex("\xFF\x80" ^ "\xC3\xA9"),
     ':', bin2hex("\xFF\x80" | "\xC3\xA9x"), ':', bin2hex(~"\xC3\xA9"), "\n";
echo bin2hex(date('é😀', 0)), "\n";
