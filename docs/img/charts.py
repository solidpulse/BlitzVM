#!/usr/bin/env python3
"""Generates the README charts (light and dark SVGs) from the numbers in docs/COMPARISON.md.

    python3 docs/img/charts.py
"""
from pathlib import Path
from xml.sax.saxutils import escape

OUT = Path(__file__).resolve().parent

THEMES = {
    "light": dict(bg="#F6F8FA", border="#D0D7DE", ink="#1F2328", muted="#59636E", track="#E6EAEF",
                  us="#D98E04", other="#AEB6C2", us_text="#9A6200"),
    "dark": dict(bg="#161B22", border="#30363D", ink="#E6EDF3", muted="#8B949E", track="#21262D",
                 us="#FFBE3B", other="#3D4654", us_text="#FFBE3B"),
}

W = 760
PAD = 24
LABEL_W = 210
VALUE_W = 110
SANS = "-apple-system, BlinkMacSystemFont, 'Segoe UI', Helvetica, Arial, sans-serif"
MONO = "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace"


def fmt(v, unit):
    if unit == "x":
        return f"{v:.1f}x"
    if unit == "ms":
        return (f"{v:.1f}" if v < 10 else f"{round(v):,}") + " ms"
    return f"{round(v):,} {unit}"


def chart(name, title, subtitle, groups, unit, footer, ref=None, top=None):
    """groups: list of (group_label or None, [(label, note, value, is_us), ...]); each group has its own scale.
    ref: (value, label) draws a dashed reference line at that value; top fixes the scale's maximum."""
    for theme, c in THEMES.items():
        y = PAD
        parts = []
        parts.append(f'<text x="{PAD}" y="{y + 16}" font-family="{SANS}" font-size="17" font-weight="600" fill="{c["ink"]}">{escape(title)}</text>')
        y += 26
        parts.append(f'<text x="{PAD}" y="{y + 12}" font-family="{MONO}" font-size="11.5" fill="{c["muted"]}">{escape(subtitle)}</text>')
        y += 30
        bar_x = PAD + LABEL_W
        bar_w = W - PAD * 2 - LABEL_W - VALUE_W
        for group_label, rows in groups:
            if group_label:
                parts.append(f'<text x="{PAD}" y="{y + 11}" font-family="{MONO}" font-size="11.5" fill="{c["muted"]}">{escape(group_label)}</text>')
                y += 22
            scale = top or max(v for _, _, v, _ in rows)
            group_top = y
            for label, note, v, us in rows:
                row_h = 34 if note else 28
                weight = "600" if us else "400"
                parts.append(f'<text x="{PAD}" y="{y + 15}" font-family="{SANS}" font-size="14" font-weight="{weight}" fill="{c["ink"]}">{escape(label)}</text>')
                if note:
                    parts.append(f'<text x="{PAD}" y="{y + 29}" font-family="{SANS}" font-size="11" fill="{c["muted"]}">{escape(note)}</text>')
                parts.append(f'<rect x="{bar_x}" y="{y + 5}" width="{bar_w}" height="12" rx="4" fill="{c["track"]}"/>')
                w = max(4, bar_w * v / scale)
                parts.append(f'<rect x="{bar_x}" y="{y + 5}" width="{w:.1f}" height="12" rx="4" fill="{c["us"] if us else c["other"]}"/>')
                parts.append(f'<text x="{W - PAD}" y="{y + 16}" text-anchor="end" font-family="{MONO}" font-size="13" '
                             f'font-weight="{weight}" fill="{c["us_text"] if us else c["ink"]}">{fmt(v, unit)}</text>')
                y += row_h
            if ref:
                rx = bar_x + bar_w * ref[0] / scale
                parts.append(f'<line x1="{rx:.1f}" y1="{group_top}" x2="{rx:.1f}" y2="{y - 6}" stroke="{c["ink"]}" stroke-width="1" stroke-dasharray="3 3"/>')
                parts.append(f'<text x="{rx + 5:.1f}" y="{y + 8}" font-family="{MONO}" font-size="11" fill="{c["muted"]}">{escape(ref[1])}</text>')
                y += 14
            y += 10
        parts.append(f'<line x1="{PAD}" y1="{y}" x2="{W - PAD}" y2="{y}" stroke="{c["border"]}"/>')
        y += 22
        parts.append(f'<text x="{PAD}" y="{y}" font-family="{MONO}" font-size="12" font-weight="600" fill="{c["us_text"]}">{escape(footer)}</text>')
        h = y + PAD - 4
        svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{h}" viewBox="0 0 {W} {h}" role="img" aria-label="{escape(title)}">'
               f'<rect x="0.5" y="0.5" width="{W - 1}" height="{h - 1}" rx="10" fill="{c["bg"]}" stroke="{c["border"]}"/>'
               + "".join(parts) + "</svg>\n")
        (OUT / f"{name}-{theme}.svg").write_text(svg)


chart("latency", "A quick request while a slow one runs",
      "ms until a hello request is answered while a 4 s CPU loop holds the only worker. Lower is better.",
      [(None, [
          ("PHP on BlitzVM", None, 5, True),
          ("JavaScript on BlitzVM", None, 25, True),
          ("Bun", "1.4.4", 3410, False),
          ("php-fpm", "opcache", 3450, False),
          ("Swoole", "default", 3480, False),
          ("Node.js", "22.14", 3780, False),
      ])], "ms", "BlitzVM: 5 to 25 ms. The rest: over 3.4 s")

chart("api", "PHP: an API-style request",
      "wait 20 ms on a backend, then real work. 4 workers, 32 connections. Higher is better.",
      [(None, [
          ("BlitzVM", "p99 73 ms", 523, True),
          ("Swoole", "default, p99 237 ms", 373, False),
          ("php-fpm", "nginx + tracing JIT, p99 274 ms", 131, False),
      ])], "req/s", "4x php-fpm, 1.4x Swoole, a third of Swoole's p99")

# (kernel, BlitzVM ms, php-fpm tracing JIT ms, Swoole ms), medians from SCENARIO=compute on 2026-11-13
KERNELS = [
    ("collatz to 300,000", 91.2, 228.5, 477.9),
    ("pixel loop, small helpers", 10.8, 25.6, 64.7),
    ("mandelbrot 600 x 600", 27.2, 46.1, 229.8),
    ("tak(24, 16, 8)", 8.6, 14.4, 35.0),
    ("gcd sum, n = 600", 9.1, 10.7, 27.1),
    ("spectral-norm, n = 300", 41.4, 48.8, 111.0),
]


def ms(v):
    return f"{v:.1f} ms" if v < 10 else f"{round(v):,} ms"


chart("compute", "PHP-BlitzVM: how much faster than PHP's own JIT",
      "times faster than php-fpm with the tracing JIT, warm, one worker. Higher is better.",
      [(None, [(k, f"{ms(us)} vs {ms(jit)} on the JIT, Swoole {ms(sw)}", jit / us, True) for k, us, jit, sw in KERNELS])],
      "x", "1.2x to 2.5x faster than PHP's JIT; 2.7x to 8.4x faster than Swoole",
      ref=(1.0, "1x = PHP's JIT"), top=3.0)

chart("js", "BlitzVM-JS vs Node.js and Bun: time to run a CPU-heavy loop",
      "ms for one run of a number-crunching function, no I/O, warm. Lower is better.",
      [("collatz to 300,000", [
          ("BlitzVM-JS", None, 179.3, True), ("Node.js", "22.14, V8", 315.9, False), ("Bun", "1.4.4, JavaScriptCore", 426.7, False)]),
       ("primes to 2,000,000", [
          ("BlitzVM-JS", None, 128.1, True), ("Bun", "1.4.4, JavaScriptCore", 133.8, False), ("Node.js", "22.14, V8", 149.3, False)])],
      "ms", "1.8x Node.js on collatz; ahead of both on primes")
