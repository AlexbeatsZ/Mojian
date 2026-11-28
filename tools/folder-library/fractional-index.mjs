// Fractional ordering keys, ported from rocicorp/fractional-indexing (CC0-1.0, see THIRD_PARTY_NOTICES.md).
// Every client uses the same algorithm and compares keys ordinally (ASCII), so inserting an item between two
// others edits only that item's own file.
const DIGITS = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz';
const ZERO = DIGITS[0];

function midpoint(a, b) {
  if (b !== null && a >= b) throw new Error(`${a} >= ${b}`);
  if (a.slice(-1) === ZERO || (b && b.slice(-1) === ZERO)) throw new Error('trailing zero');
  if (b) {
    let n = 0;
    while ((a[n] || ZERO) === b[n]) n++;
    if (n > 0) return b.slice(0, n) + midpoint(a.slice(n), b.slice(n));
  }
  const digitA = a ? DIGITS.indexOf(a[0]) : 0;
  const digitB = b !== null ? DIGITS.indexOf(b[0]) : DIGITS.length;
  if (digitB - digitA > 1) return DIGITS[Math.round(0.5 * (digitA + digitB))];
  if (b && b.length > 1) return b.slice(0, 1);
  return DIGITS[digitA] + midpoint(a.slice(1), null);
}

function integerLength(head) {
  if (head >= 'a' && head <= 'z') return head.charCodeAt(0) - 97 + 2;
  if (head >= 'A' && head <= 'Z') return 90 - head.charCodeAt(0) + 2;
  throw new Error(`invalid order key head: ${head}`);
}

function integerPart(key) {
  const length = integerLength(key[0]);
  if (length > key.length) throw new Error(`invalid order key: ${key}`);
  return key.slice(0, length);
}

function validate(key) {
  if (key === 'A' + ZERO.repeat(26)) throw new Error(`invalid order key: ${key}`);
  if (key.slice(integerPart(key).length).slice(-1) === ZERO) throw new Error(`invalid order key: ${key}`);
}

function increment(x) {
  const [head, ...digits] = x.split('');
  let carry = true;
  for (let i = digits.length - 1; carry && i >= 0; i--) {
    const d = DIGITS.indexOf(digits[i]) + 1;
    if (d === DIGITS.length) digits[i] = ZERO; else { digits[i] = DIGITS[d]; carry = false; }
  }
  if (!carry) return head + digits.join('');
  if (head === 'Z') return 'a' + ZERO;
  if (head === 'z') return null;
  const next = String.fromCharCode(head.charCodeAt(0) + 1);
  if (next > 'a') digits.push(ZERO); else digits.pop();
  return next + digits.join('');
}

function decrement(x) {
  const [head, ...digits] = x.split('');
  let borrow = true;
  for (let i = digits.length - 1; borrow && i >= 0; i--) {
    const d = DIGITS.indexOf(digits[i]) - 1;
    if (d === -1) digits[i] = DIGITS.slice(-1); else { digits[i] = DIGITS[d]; borrow = false; }
  }
  if (!borrow) return head + digits.join('');
  if (head === 'a') return 'Z' + DIGITS.slice(-1);
  if (head === 'A') return null;
  const next = String.fromCharCode(head.charCodeAt(0) - 1);
  if (next < 'Z') digits.push(DIGITS.slice(-1)); else digits.pop();
  return next + digits.join('');
}

/** A key strictly between `a` and `b`; `null` means unbounded. */
export function keyBetween(a, b) {
  if (a !== null) validate(a);
  if (b !== null) validate(b);
  if (a !== null && b !== null && a >= b) throw new Error(`${a} >= ${b}`);
  if (a === null) {
    if (b === null) return 'a' + ZERO;
    const ib = integerPart(b);
    const fb = b.slice(ib.length);
    if (ib === 'A' + ZERO.repeat(26)) return ib + midpoint('', fb);
    if (ib < b) return ib;
    const result = decrement(ib);
    if (result === null) throw new Error('cannot decrement any more');
    return result;
  }
  if (b === null) {
    const ia = integerPart(a);
    const next = increment(ia);
    return next === null ? ia + midpoint(a.slice(ia.length), null) : next;
  }
  const ia = integerPart(a);
  const ib = integerPart(b);
  if (ia === ib) return ia + midpoint(a.slice(ia.length), b.slice(ib.length));
  const next = increment(ia);
  if (next === null) throw new Error('cannot increment any more');
  return next < b ? next : ia + midpoint(a.slice(ia.length), null);
}

/** `count` ascending keys after `a` (used to number existing items in order). */
export function keysAfter(a, count) {
  const keys = [];
  for (let key = a; keys.length < count;) keys.push(key = keyBetween(key, null));
  return keys;
}
