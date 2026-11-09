const maxInt32 = 2147483647;
console.log(maxInt32);
console.log(maxInt32 + 1);
console.log(maxInt32 + 100);

const p1 = 46341 * 46341;
console.log(p1);

const p2 = 1000000 * 1000000;
console.log(p2);

const big = 9007199254740991;
console.log(big);
console.log(big + 1);
console.log(big + 2);

let acc = 1;
for (let i = 0; i < 35; i++) {
    acc = acc * 2;
}
console.log(acc);

const largeDiv = (maxInt32 + 1) / 2;
console.log(largeDiv);
