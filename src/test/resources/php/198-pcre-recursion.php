<?php
// PCRE recursion and subroutine calls, expanded inline (WordPress kses CSS functions, PHPMailer email check).
$css = 'color: var(--a, calc(1px + (2px * 3))); width: min(10px, max(1px, 2px)) foo(bar) calc((1)';
var_dump(preg_replace('/\b(?:var|calc|min|max)(\((?:[^()]|(?1))*\))/', '', $css));
var_dump(preg_match('/^(\((?:[^()]++|(?1))*\))$/', '((a)(b(c)))', $m), $m);
var_dump(preg_match('/^(?<p>\[(?:[^\[\]]|(?&p))*\])$/', '[a[b[c]]d]', $m), $m['p']);
var_dump(preg_match('/^(a|b(?R)c)$/', 'bbacc'), preg_match('/^(?:a|b(?R)c)$/', 'bbbaccc'));
var_dump(preg_match('/(\d+)-(?1)-(?-1)/', 'x 12-345-6 y', $m), $m);
var_dump(preg_match('/(?P<w>[a-z]+) (?P>w)/', 'foo bar', $m), $m);
var_dump(preg_match('/(?+1)=(\d)/', '7=8', $m), $m);
var_dump(preg_match('/^\g<x> (?<x>\w)$/', 'a b', $m), $m);
$email = '/^(?!(?>(?1)"?(?>\\\[ -~]|[^"])"?(?1)){255,})(?!(?>(?1)"?(?>\\\[ -~]|[^"])"?(?1)){65,}@)((?>(?>(?>((?>(?>(?>\x0D\x0A)?[\t ])+|(?>[\t ]*\x0D\x0A)?[\t ]+)?)(\((?>(?2)(?>[\x01-\x08\x0B\x0C\x0E-\'*-\[\]-\x7F]|\\\[\x00-\x7F]|(?3)))*(?2)\)))+(?2))|(?2))?)([!#-\'*+\/-9=?^-~-]+|"(?>(?2)(?>[\x01-!#-\[\]-\x7F]|\\\[\x00-\x7F]))*(?2)")(?>(?1)\.(?1)(?4))*(?1)@(?!(?1)[a-z0-9-]{64,})(?1)(?>([a-z0-9](?>[a-z0-9-]*[a-z0-9])?)(?>(?1)\.(?!(?1)[a-z0-9-]{64,})(?1)(?5)){0,126}|\[(?:(?>IPv6:(?>([a-f0-9]{1,4})(?>:(?6)){7}|(?!(?:.*[a-f0-9][:\]]){8,})((?6)(?>:(?6)){0,6})?::(?7)?))|(?>(?>IPv6:(?>(?6)(?>:(?6)){5}:|(?!(?:.*[a-f0-9]:){6,})(?8)?::(?>((?6)(?>:(?6)){0,4}):)?))?(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])(?>\.(?9)){3}))\])(?1)$/isD';
foreach (['user@example.com', 'a.b@[127.0.0.1]', 'bad@@x', '"q u"@ex.org'] as $e) echo $e, ' ', preg_match($email, $e), "\n";
