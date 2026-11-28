<?php
$f = tempnam(sys_get_temp_dir(), 'ra');
file_put_contents($f, "line one\nline two\n" . str_repeat("x", 20000) . "\nend");

// whole file, fresh stream
$h = fopen($f, 'r');
$s = stream_get_contents($h);
var_dump(strlen($s), substr($s, -4), feof($h));
var_dump(stream_get_contents($h));
fclose($h);

// after a buffered fgets: the rest comes partly from the buffer
$h = fopen($f, 'r');
var_dump(fgets($h));
$s = stream_get_contents($h);
var_dump(strlen($s), substr($s, 0, 9), substr($s, -4));
fclose($h);

// after a seek past the buffer, and with a max length and offset
$h = fopen($f, 'r');
fread($h, 3);
fseek($h, 15000);
var_dump(strlen(stream_get_contents($h)));
var_dump(stream_get_contents($h, 5, 5));
var_dump(stream_get_contents($h, -1, 20010));
fclose($h);

// the file grows after fopen
$h = fopen($f, 'r');
fgets($h);
file_put_contents($f, "+grown", FILE_APPEND);
$s = stream_get_contents($h);
var_dump(strlen($s), substr($s, -10));
fclose($h);

// read+write stream: write, rewind, read all
$h = fopen($f, 'w+');
fwrite($h, "abc");
var_dump(stream_get_contents($h));
rewind($h);
var_dump(stream_get_contents($h));
fwrite($h, "def");
rewind($h);
var_dump(stream_get_contents($h), feof($h));
fclose($h);

// empty file and file_get_contents through a stream wrapper path
file_put_contents($f, "");
$h = fopen($f, 'r');
var_dump(stream_get_contents($h), feof($h));
fclose($h);
file_put_contents($f, "0123456789");
var_dump(file_get_contents('file://' . $f, false, null, 3, 4));
var_dump(file_get_contents('file://' . $f, false, null, 6));
var_dump(md5_file($f) === md5("0123456789"));
unlink($f);
