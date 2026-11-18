// Ponctuation, typographie française, double espace, majuscules (SPEC §5.6, §5.7).

export const NNBSP = ' '; // espace fine insécable
export const NBSP = ' '; // espace insécable

export const PUNCT_BAR = [',', '.', '?', '!', ':', '…', "'"];
const FINAL = new Set(['.', '?', '!', '…']);

/** Espaces avant / après un signe (tableau du §5.6). */
export function spacingFor(sign, french) {
  if (sign === "'") return { before: '', after: '' };
  if (!french) return { before: '', after: ' ' };
  switch (sign) {
    case ';':
    case '!':
    case '?':
    case '»':
      return { before: NNBSP, after: ' ' };
    case ':':
      return { before: NBSP, after: ' ' };
    case '«':
      return { before: '', after: NNBSP };
    default:
      return { before: '', after: ' ' };
  }
}

export function isSentenceEnd(sign) {
  return FINAL.has(sign);
}

/**
 * Applique un signe. `text` est le texte avant le curseur ; si `autoSpace`
 * est vrai il se termine par l'espace automatique, qui est remplacée.
 * @returns {{text: string, autoSpace: boolean}}
 */
export function applySign(text, sign, { autoSpace, french }) {
  if (typeof sign !== 'string' || sign === '') throw new RangeError('Signe de ponctuation vide.');
  let base = autoSpace && text.endsWith(' ') ? text.slice(0, -1) : text;
  const { before, after } = spacingFor(sign, french);
  if (before !== '' && base !== '' && !/[\s  ]$/.test(base)) base += before;
  if (sign === "'") return { text: base + sign, autoSpace: false };
  return { text: base + sign + after, autoSpace: after === ' ' };
}

/** Double espace : l'espace automatique devient « . » + espace. */
export function applyDoubleSpace(text) {
  if (!text.endsWith(' ')) return text;
  return text.slice(0, -1) + '. ';
}

/**
 * Majuscule automatique (§5.7) : début de champ, après . ? ! … suivis d'espace(s),
 * ou après un saut de ligne.
 */
export function shouldCapitalize(text) {
  if (text === '') return true;
  if (/\n$/.test(text)) return true;
  return /[.?!…][\s  ]+$/u.test(text);
}
