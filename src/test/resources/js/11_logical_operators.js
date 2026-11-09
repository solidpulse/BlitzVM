console.log(0 || 'x');
console.log('a' && 'b');
console.log('' || 0);

console.log(1 || 2);
console.log(0 || 42);
console.log('hello' || 'world');
console.log('' || 'fallback');

console.log(1 && 2);
console.log(0 && 42);
console.log('hello' && 'world');
console.log('' && 'world');

console.log(!true);
console.log(!false);
console.log(!0);
console.log(!'');
console.log(!'hello');
console.log(!42);

const val = '' || 0 || 'default';
console.log(val);
