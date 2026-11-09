const timers = require('node:timers/promises');

function mandelbrot(n) {
    let count = 0;
    for (let y = 0; y < n; y++) {
        const ci = 2.0 * y / n - 1.0;
        for (let x = 0; x < n; x++) {
            const cr = 2.0 * x / n - 1.5;
            let zr = 0.0;
            let zi = 0.0;
            let i = 0;
            while (i < 50 && zr * zr + zi * zi <= 4.0) {
                const t = zr * zr - zi * zi + cr;
                zi = 2.0 * zr * zi + ci;
                zr = t;
                i++;
            }
            if (i === 50) {
                count++;
            }
        }
    }
    return count;
}

function collatz(limit) {
    let best = 0;
    let bestStart = 0;
    for (let start = 1; start < limit; start++) {
        let x = start;
        let len = 1;
        while (x !== 1) {
            if (x % 2 === 0) {
                x = x / 2;
            } else {
                x = 3 * x + 1;
            }
            len++;
        }
        if (len > best) {
            best = len;
            bestStart = start;
        }
    }
    return bestStart;
}

function tak(x, y, z) {
    if (y < x) {
        return tak(tak(x - 1, y, z), tak(y - 1, z, x), tak(z - 1, x, y));
    }
    return z;
}

function count_primes(limit) {
    let count = limit >= 2 ? 1 : 0;
    for (let i = 3; i < limit; i += 2) {
        let prime = true;
        for (let d = 3; d * d <= i; d += 2) {
            if (i % d === 0) {
                prime = false;
                break;
            }
        }
        if (prime) {
            count++;
        }
    }
    return count;
}

function gcd(a, b) {
    while (b !== 0) {
        const t = a % b;
        a = b;
        b = t;
    }
    return a;
}

function gcd_sum(n) {
    let sum = 0;
    for (let i = 1; i <= n; i++) {
        for (let j = 1; j <= n; j++) {
            sum += gcd(i, j);
        }
    }
    return sum;
}

function clamp(v, lo, hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

function mix(a, b, t) {
    return a + (b - a) * t / 8;
}

function shade(x, y) {
    const r = clamp(mix(x % 256, y % 256, (x + y) % 9), 16, 240);
    const g = clamp(mix(y % 256, 255 - x % 256, x % 9), 16, 240);
    return r * 3 + g;
}

function render_pixels(n) {
    let acc = 0;
    for (let y = 0; y < n; y++) {
        for (let x = 0; x < n; x++) {
            acc += shade(x, y);
        }
    }
    return Math.trunc(acc);
}

function fib(n) {
    if (n < 2) {
        return n;
    }
    return fib(n - 1) + fib(n - 2);
}

module.exports = async (req, res) => {
    const k = req.query.k || 'mandelbrot';
    const n = Number(req.query.n || 0);
    const t0 = performance.now();
    let r = 0;
    if (k === 'mandelbrot') {
        r = mandelbrot(n || 400);
    } else if (k === 'collatz') {
        r = collatz(n || 300000);
    } else if (k === 'tak') {
        r = tak(n || 24, 16, 8);
    } else if (k === 'primes') {
        r = count_primes(n || 2000000);
    } else if (k === 'gcd') {
        r = gcd_sum(n || 600);
    } else if (k === 'pixels') {
        r = render_pixels(n || 800);
    } else if (k === 'fib') {
        r = fib(n || 27);
    } else if (k === 'api') {
        await timers.setTimeout(20);
        r = render_pixels(n || 300);
    } else {
        res.end('unknown kernel\n');
        return;
    }
    const ms = performance.now() - t0;
    res.end(`${k} = ${r} in ${ms.toFixed(1)} ms\n`);
};
