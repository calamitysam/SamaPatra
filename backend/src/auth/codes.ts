import { randomInt } from 'node:crypto';

// No I, O, 0 or 1, so codes are easy to read and type.
const ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';

export function generateJoinCode(): string {
  let code = '';
  for (let i = 0; i < 6; i++) code += ALPHABET[randomInt(ALPHABET.length)];
  return code;
}

export function generatePin(): string {
  return randomInt(0, 10000).toString().padStart(4, '0');
}