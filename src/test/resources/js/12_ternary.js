const a = 10;
const b = 20;

const max = a > b ? a : b;
console.log(max);

const min = a < b ? a : b;
console.log(min);

const sign = a > 0 ? 'pos' : (a < 0 ? 'neg' : 'zero');
console.log(sign);

const zero = 0;
const zeroSign = zero > 0 ? 'pos' : (zero < 0 ? 'neg' : 'zero');
console.log(zeroSign);

const neg = -5;
const negSign = neg > 0 ? 'pos' : (neg < 0 ? 'neg' : 'zero');
console.log(negSign);

const label = a === 10 ? 'ten' : 'other';
console.log(label);

const result = (a > 5 ? 'gt5' : 'le5') + '-' + (b > 15 ? 'gt15' : 'le15');
console.log(result);
