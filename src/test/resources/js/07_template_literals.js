const name = 'world';
console.log(`hello, ${name}!`);

const a = 10;
const b = 20;
console.log(`${a} + ${b} = ${a + b}`);

const product = `product: ${a * b}`;
console.log(product);

const multiline = `line1
line2 ${a}
line3`;
console.log(multiline);

const nested = `status: ${a < b ? 'ok' : 'fail'}`;
console.log(nested);

const empty = ``;
console.log(empty);
console.log(`len=${empty.length}`);
