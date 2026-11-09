const obj = { x: 10, y: 20, tag: 'point' };
console.log(obj.x);
console.log(obj.y);
console.log(obj.tag);

const p = { a: 1, b: 'hello' };
console.log(p.a, p.b);

console.log(Number('42'));
console.log(Number('4.5'));
console.log(Number(''));
console.log(Number('0'));

console.log(String(3.0));
console.log(String(42));
console.log(String(3.14));

console.log(parseInt('17', 10));
console.log(parseInt('42', 10));
console.log(parseInt('0', 10));
console.log(parseInt('-99', 10));

console.log('val:', obj.x, 'str:', String(obj.y), 'parsed:', parseInt('100', 10));
