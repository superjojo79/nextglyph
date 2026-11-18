// Normalisation des mots (SPEC §3 « Préfixe », §5.8).
// Forme normalisée : minuscules, sans accents, « œ » -> « oe », « æ » -> « ae ».
// La clé d'indexation ne garde en plus que les lettres a-z (apostrophes et
// traits d'union sont restitués à la validation, cf. §5.8).

export const LETTERS = 'abcdefghijklmnopqrstuvwxyz'.split('');

function assertString(s) {
  if (typeof s !== 'string') {
    throw new TypeError(`Une chaîne de caractères est attendue, reçu : ${typeof s}`);
  }
}

/** Minuscules, sans accents ; conserve apostrophes et traits d'union. */
export function normalize(s) {
  assertString(s);
  return s
    .toLowerCase()
    .replace(/œ/g, 'oe')
    .replace(/æ/g, 'ae')
    .replace(/[’‘`]/g, "'")
    .normalize('NFD')
    .replace(/\p{M}+/gu, '');
}

/** Clé d'indexation : forme normalisée réduite aux lettres a-z. */
export function toKey(s) {
  return normalize(s).replace(/[^a-z]/g, '');
}

/** Vrai si la chaîne est entièrement composée de lettres a-z non vides. */
export function isKey(s) {
  return typeof s === 'string' && /^[a-z]+$/.test(s);
}

/** Majuscule sur la première lettre (Unicode-safe), le reste inchangé. */
export function capitalizeFirst(s) {
  assertString(s);
  if (s === '') return s;
  const [first] = s;
  return first.toLocaleUpperCase() + s.slice(first.length);
}
