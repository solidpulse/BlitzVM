const empty = '';
console.log(empty.length);

const s = 'hello';
console.log(s.length);

const sentence = 'the quick brown fox';
console.log(sentence.length);

const concat = s + ' ' + sentence;
console.log(concat.length);

const numStr = '12345';
console.log(numStr.length);

let total = 0;
total += 'foo'.length;
total += 'barbaz'.length;
console.log(total);
