let sum = 0;
for (let i = 0; i < 10; i++) {
    sum += i;
}
console.log(sum);

let evens = 0;
for (let i = 0; i < 10; i++) {
    if (i % 2 !== 0) {
        continue;
    }
    evens += i;
}
console.log(evens);

let firstOver20 = 0;
for (let i = 1; i < 100; i++) {
    firstOver20 += i;
    if (firstOver20 > 20) {
        break;
    }
}
console.log(firstOver20);

let count = 0;
let w = 10;
while (w > 0) {
    w--;
    if (w === 5) {
        continue;
    }
    if (w === 2) {
        break;
    }
    count++;
}
console.log(count, w);
