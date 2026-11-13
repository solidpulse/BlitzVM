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
LABEL_W = 190
VALUE_W = 110
SANS = "-apple-system, BlinkMacSystemFont, 'Segoe UI', Helvetica, Arial, sans-serif"
MONO = "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace"


def fmt(v, unit):
    if unit == "ms":
        return (f"{v:.1f}" if v < 10 else f"{round(v):,}") + " ms"
    return f"{round(v):,} {unit}"


def chart(name, title, subtitle, groups, unit, footer):
    """groups: list of (group_label or None, [(label, note, value, is_us), ...]); each group has its own scale."""
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
            top = max(v for _, _, v, _ in rows)
            for label, note, v, us in rows:
                row_h = 34 if note else 28
                weight = "600" if us else "400"
                parts.append(f'<text x="{PAD}" y="{y + 15}" font-family="{SANS}" font-size="14" font-weight="{weight}" fill="{c["ink"]}">{escape(label)}</text>')
                if note:
                    parts.append(f'<text x="{PAD}" y="{y + 29}" font-family="{SANS}" font-size="11" fill="{c["muted"]}">{escape(note)}</text>')
                parts.append(f'<rect x="{bar_x}" y="{y + 5}" width="{bar_w}" height="12" rx="4" fill="{c["track"]}"/>')
                w = max(4, bar_w * v / top)
                parts.append(f'<rect x="{bar_x}" y="{y + 5}" width="{w:.1f}" height="12" rx="4" fill="{c["us"] if us else c["other"]}"/>')
                parts.append(f'<text x="{W - PAD}" y="{y + 16}" text-anchor="end" font-family="{MONO}" font-size="13" '
                             f'font-weight="{weight}" fill="{c["us_text"] if us else c["ink"]}">{fmt(v, unit)}</text>')
                y += row_h
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
          ("BlitzVM", None, 293, True),
          ("Swoole", "default", 193, False),
          ("php-fpm", "nginx + tracing JIT", 136, False),
      ])], "req/s", "1.5x Swoole, 2.2x php-fpm")

chart("compute", "PHP: compute vs PHP's own JIT",
      "ms per run, warm. php-fpm has the tracing JIT on; Swoole can't use it. Lower is better.",
      [("tak(24, 16, 8), lots of recursion", [
          ("BlitzVM", None, 8.2, True), ("php-fpm", "tracing JIT", 14.6, False), ("Swoole", None, 36.9, False)]),
       ("collatz to 300,000, integer branches", [
          ("BlitzVM", None, 144.1, True), ("php-fpm", "tracing JIT", 237.8, False), ("Swoole", None, 505.2, False)]),
       ("pixel loop built from tiny helper functions", [
          ("BlitzVM", None, 20.5, True), ("php-fpm", "tracing JIT", 27.3, False), ("Swoole", None, 69.5, False)])],
      "ms", "1.3x to 1.8x faster than PHP's own JIT")
