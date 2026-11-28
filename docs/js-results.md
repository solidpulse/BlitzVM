# BlitzVM-JS results (kept off the website)

The website is PHP-only for now. These are the JavaScript front end's numbers that were on it,
measured on 13 November 2026, kept here for when the JavaScript work resumes.

## A quick request while a slow one runs

Milliseconds until a hello request is answered while a 4 s CPU loop holds the only worker (lower
is better). The same handler files (`www/spin.js`, `www/hello.js`) on each server; each server
answered one hello request before the test started.

| runtime | ms |
|---|---:|
| BlitzVM-JS | 25 |
| Bun 1.4.4 | 3,410 |
| Node.js 22.14 | 3,780 |

For comparison on the same test: PHP on BlitzVM 5 ms, php-fpm (opcache) 3,450 ms, Swoole
(default) 3,480 ms.

## CPU-heavy loops

Milliseconds for one run of a number-crunching function, no I/O, warm, lower is better. The
kernels ported to JavaScript (`www/compute.js`), one process per runtime, run back to back on
BlitzVM, Node.js 22.14 and Bun 1.4.4; best of five rounds after warm-up.

| kernel | BlitzVM-JS | Node.js 22.14 (V8) | Bun 1.4.4 (JavaScriptCore) |
|---|---:|---:|---:|
| collatz to 300,000 | 179.3 | 315.9 | 426.7 |
| primes to 2,000,000 | 128.1 | 149.3 | 133.8 |

The site's summary line was "1.8x faster than Node.js on integer loops (collatz, 179 ms vs
316 ms on V8)". The JavaScript front end is a prototype that shares BlitzVM's core (scheduler,
per-request contexts) with PHP.

Machine: 8 cores, Linux 6.8, load generator and servers on the same host; BlitzVM v0.1 on JDK 25
with the Graal compiler from GraalVM Community 25.5.
