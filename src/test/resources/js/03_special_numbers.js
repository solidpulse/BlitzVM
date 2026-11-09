const zero = 0;
const negZero = -0;

console.log(0 / 0);
console.log(1 / 0);
console.log(-1 / 0);

console.log(1 / zero);
console.log(-1 / zero);
console.log(1 / negZero);

const inf = 1 / 0;
console.log(inf + 1);
console.log(inf * 2);
console.log(inf - inf);
console.log(inf / inf);

const nan = 0 / 0;
console.log(nan + 1);
console.log(nan === nan);
console.log(nan == nan);
console.log(inf > 999999999);
console.log(-inf < -999999999);
