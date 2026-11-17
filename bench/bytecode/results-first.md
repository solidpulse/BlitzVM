# First completed measurement batch

Retained to expose load sensitivity rather than silently dropping noisy data. Raw data is
`results.json`. Three process measurements per cell; each process uses the median of its
last three of ten rounds. All outputs matched PHP. One-minute pre-run load: **5.25–9.59**.

Milliseconds: **median (min–max)**.

| Benchmark | Guest mode | PHP opcache/no JIT | AST | Bytecode | AST/BC |
|---|---|---:|---:|---:|---:|
| 1: WP array get | interpreter | 90.36 (78.03–92.07) | 1670.96 (1388.51–2294.40) | 668.11 (545.44–760.60) | 2.50x |
| 2: 50 functions | interpreter | 22.57 (20.68–23.92) | 227.95 (215.39–327.39) | 142.53 (139.91–153.79) | 1.60x |
| 3: strings | interpreter | 13.25 (12.88–13.42) | 94.64 (88.49–103.23) | 62.24 (61.47–70.28) | 1.52x |
| 4: foreach | interpreter | 4.17 (4.12–4.24) | 19.39 (18.44–19.85) | 28.38 (28.07–31.96) | 0.68x |
| 1: WP array get | Graal enabled | 39.90 (37.63–40.69) | 118.84 (116.99–120.19) | 45.41 (42.00–48.67) | 2.62x |
| 2: 50 functions | Graal enabled | 26.56 (18.82–41.42) | 71.23 (52.07–135.33) | 34.93 (28.20–58.71) | 2.04x |
| 3: strings | Graal enabled | 23.36 (23.24–24.76) | 107.46 (96.88–131.19) | 106.73 (94.22–125.62) | 1.01x |
| 4: foreach | Graal enabled | 8.98 (7.97–9.45) | 25.72 (24.91–27.45) | 21.11 (18.29–24.47) | 1.22x |

The first measurement invocation was interrupted by the shell timeout before completion;
that incomplete dataset was replaced by this full batch after fixing an argument-evaluation
COW issue found during code review. It is not used as a comparison baseline. A second full
batch on the **same Java build** is collected as `results-repeat.json` because the first
batch had high changing load and several >2x absolute baseline shifts. No processes owned
by other agents were stopped or adjusted.
