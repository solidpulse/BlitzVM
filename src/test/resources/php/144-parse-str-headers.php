<?php
parse_str('a=1&b[]=2&b[]=3&c[k]=v&d=x+y%21&e&f.g=1&h i=2&j[x][y]=z&k[]=1&k[5]=2&k[]=3', $out);
var_dump($out);
parse_str('a[b=1&c]=2&[x]=3&=4&m[][]=5&m[][]=6&n[a][]=7&n[a][]=8&%61%5B%5D=9&q=%E2%82%AC&a=last', $out2);
var_dump($out2);
parse_str('', $empty);
var_dump($empty);
// In the CLI nothing has been output yet, so headers can still be set and listed.
var_dump(headers_sent());
