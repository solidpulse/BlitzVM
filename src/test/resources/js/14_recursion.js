function fib(n) {
    if (n < 2) {
        return n;
    }
    return fib(n - 1) + fib(n - 2);
}

function gcd(a, b) {
    if (b === 0) {
        return a;
    }
    return gcd(b, a % b);
}

function tak(x, y, z) {
    if (y < x) {
        return tak(tak(x - 1, y, z), tak(y - 1, z, x), tak(z - 1, x, y));
    }
    return z;
}

console.log(fib(0));
console.log(fib(1));
console.log(fib(10));
console.log(gcd(48, 18));
console.log(gcd(101, 10));
console.log(tak(12, 8, 4));
console.log(tak(18, 12, 6));
