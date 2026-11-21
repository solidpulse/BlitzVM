<?php
// The patterns WordPress runs most on the front page, on realistic page HTML and CSS.
// These are the same strings as the WordPress sources; the outputs are the ones php
// produces, so the fast paths in the runtime cannot change match positions.

function show($label, $value) {
    echo $label, ': ', var_export($value, true), "\n";
}

$html = '<!DOCTYPE html><html lang="en-US"><head>'
    . '<meta charset="UTF-8">'
    . '<style id="global-styles-inline-css">'
    . 'body{background-color:#f9f9f9;color:#333}'
    . '.wp-block-group{padding:var(--wp--preset--spacing--40)}'
    . '.wp-block-image img{margin:0 auto;width:calc(100% - 2rem)}'
    . '.hero{background-image:url("https://example.com/a b.png")}'
    . '</style></head><body class="home blog">'
    . '<!-- wp:group {"tagName":"main","style":{"spacing":{"padding":{"top":"var:preset|spacing|50"}}}} -->'
    . '<main class="wp-block-group">'
    . '<!-- wp:heading {"level":1} --><h1 class="wp-block-heading">Hello &amp; welcome</h1><!-- /wp:heading -->'
    . '<!-- wp:paragraph --><p>Text with &amp; an &#038; and a & character, plus <code>a &lt; b</code>.</p><!-- /wp:paragraph -->'
    . '<!-- wp:image {"id":7} --><figure><img src="/wp-content/uploads/2026/11/photo.jpg" alt=""/></figure><!-- /wp:image -->'
    . '<!-- wp:code --><pre><code>if (a &lt; b) { echo "x"; }</code></pre><!-- /wp:code -->'
    . '</main></body></html>';

// parse_blocks(): the block comment scanner.
$blocks = '/<!--[ \t\f\r\n]+(?P<closer>\/)?wp:(?P<namespace>[a-z][a-z0-9_-]*\/)?(?P<name>[a-z][a-z0-9_-]*)[ \t\f\r\n]+(?P<attrs>{(?:(?:[^}]+|}+(?=})|(?!}[ \t\f\r\n]+\/?-->).)*+)?}[ \t\f\r\n]+)?(?P<void>\/)?-->/s';
show('blocks', preg_match_all($blocks, $html, $m));
show('block names', $m['name']);
show('block closers', $m['closer']);
preg_match_all($blocks, $html, $mo, PREG_OFFSET_CAPTURE);
show('block offsets', $mo[0][0][1]);

// wp_normalize_path(): collapse duplicate slashes except at the start.
$paths = ['/var/www//wp-content///themes/./twentytwentyfive', 'http://example.com//a///b', '///'];
foreach ($paths as $path) {
    show('path ' . $path, preg_replace('|(?<=.)/+|', '/', $path));
}

// WP_Theme_JSON::append_to_selector(): whitespace around selector commas.
foreach ([".a,.b ,\n.c", '.single, .double', 'no-comma'] as $selector) {
    show('selector', preg_replace('~[ \t\n]*,[ \t\n]*~', ', ', trim($selector, " \t\n")));
}

// esc_url()/wp_kses_bad_protocol_once(): bare ampersands become entities.
show('amp', preg_replace('/&(?!#(?:\d+|x[a-f0-9]+);|[a-z1-4]{1,8};)/i', '&#038;', $html));
show('amp count', preg_match_all('/&(?!#(?:\d+|x[a-f0-9]+);|[a-z1-4]{1,8};)/i', $html));

// wp_kses_bad_protocol_once(): control characters, then backslash-zero pairs.
$dirty = "a\x01b\x0Bc\x1Fd\\0e\\\\0f";
show('control', preg_replace('/[\x00-\x08\x0B\x0C\x0E-\x1F]/', '', $dirty));
show('backslash zero', preg_replace('/\\\\+0+/', '', $dirty));

// HTML tag processor: lower-case closing style tags and rewrite CSS urls.
$css = '<style>.a{background:url( "x.png" )}</style><STYLE>.b{background:URL(y.png)}</STYLE>';
show('style close', preg_replace_callback('~</(?P<TAG_NAME>style)~i', fn($m) => '</' . strtolower($m['TAG_NAME']), $css));
$urls = '#(url\s*\(\s*[\'"]?\s*)([^\'"\)]+)#';
show('css url', preg_replace_callback($urls, fn($m) => $m[1] . 'data:image/png;base64,AA', $css));

// wp_html_split() short form and the plain tag splitter.
show('tag split', count(preg_split('/(<[^>]*>)/U', $html, -1, PREG_SPLIT_DELIM_CAPTURE)));
show('tag split first', preg_split('/(<[^>]*>)/U', '<p>x</p>', -1, PREG_SPLIT_DELIM_CAPTURE));
show('img tags', preg_match_all('/<(img|iframe)\s[^>]+>/', $html, $m));
show('img tags value', $m[0]);

// A link inside the content: make_clickable()/wp_embed checks.
show('https', preg_match('#(^|\s|>)https?://#i', $html));
show('https no', preg_match('#(^|\s|>)https?://#i', 'no link here'));

// The style engine's function list and custom property check.
show('css functions', preg_match_all('/\b(?:var|calc|min|max|minmax|clamp|repeat|matrix|matrix3d|perspective|rotate|rotate3d|rotateX|rotateY|rotateZ|scale|scale3d|scaleX|scaleY|scaleZ|skew|skewX|skewY|translate|translate3d|translateX|translateY|translateZ|circle|ellipse|inset|path|polygon|rect|shape|xywh)(\((?:[^()]|(?1))*\))/', $css, $m));
show('css functions value', $m[0]);
show('custom property', preg_match('/^--[a-zA-Z0-9-_]+$/', '--wp--preset--spacing--40'));

// sanitize_key() and sanitize_title_with_dashes().
show('sanitize_key', preg_replace('/[^a-z0-9_\-]/', '', strtolower('Some Key!')));
show('sanitize_title', preg_replace('/[^%a-z0-9 _-]/', '', strtolower('Hello, World!')));
show('title percent', preg_replace('|%([a-fA-F0-9][a-fA-F0-9])|', '', 'a%20b%zz'));

// is_serialized(): the token probes.
$data = [
    'a:2:{s:4:"name";s:3:"foo";s:1:"n";i:3;}',
    'O:8:"stdClass":1:{s:1:"a";i:1;}',
    'not serialized',
    's:5:"hello";',
    'i:42;',
];
foreach ($data as $value) {
    show('serialized ' . substr($value, 0, 1), preg_match('/^a:[0-9]+:/s', $value));
}
show('serialized token O', preg_match('/^O:[0-9]+:/s', $data[1]));
show('serialized token i', preg_match('/^i:[0-9.E+-]+;$/', $data[4]));
