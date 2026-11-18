// Validation par espace (SPEC §5.5) : trois cas.

import { candidates } from './distribution.js';
import { toKey } from './normalize.js';

function weight(lex, pi) {
  return (pi[lex.lang] ?? 0) / (lex.total || 1);
}

/** Cas 1 : forme à insérer pour un mot du lexique, avec restitution des accents sans ambiguïté. */
function restoreWord(key, lexicons, pi) {
  const exact = [];
  const accented = [];
  for (const lex of lexicons) {
    for (const form of lex.forms(key)) {
      if (!form.plain) continue;
      const entry = { text: form.w, score: weight(lex, pi) * form.f };
      if (form.w.toLowerCase() === key) exact.push(entry);
      else if (lex.lang === 'fr') accented.push(entry); // accents : uniquement depuis le français (§10.6)
    }
  }
  const best = (list) => list.sort((a, b) => b.score - a.score)[0].text;
  if (exact.length) return best(exact);
  if (accented.length) {
    const distinct = new Set(accented.map((e) => e.text));
    return distinct.size === 1 ? accented[0].text : key;
  }
  return null;
}

/**
 * @param {string} key  mot en cours (clé normalisée)
 * @param {object} ctx  { lexicons, pi, params: {enabled, minProb, minRatio}, rejected?: Set<string>, noAuto?: boolean, isNeighbor? }
 * @returns {{kind: 'word'|'auto'|'raw', text: string, original: string, changed: boolean, p?: number}}
 */
export function validateWord(key, ctx) {
  const { lexicons, pi, params, rejected = new Set(), noAuto = false, isNeighbor } = ctx;
  if (key === '') return { kind: 'raw', text: '', original: '', changed: false };
  const word = restoreWord(key, lexicons, pi);
  if (word !== null) return { kind: 'word', text: word, original: key, changed: word !== key };
  if (params.enabled && !noAuto) {
    const { list } = candidates(key, lexicons, pi, { isNeighbor });
    const [top, second] = list;
    if (
      top &&
      top.p >= params.minProb &&
      (!second || top.score / second.score >= params.minRatio) &&
      !rejected.has(`${key}>${top.text}`)
    ) {
      return { kind: 'auto', text: top.text, original: key, changed: true, p: top.p };
    }
  }
  return { kind: 'raw', text: key, original: key, changed: false };
}

/** Langues dont le lexique connaît le mot (forme simple ou composée) : alimente le score de langue. */
export function languageHits(text, lexicons) {
  const key = toKey(text);
  const hits = new Set();
  if (key === '') return hits;
  for (const lex of lexicons) if (lex.forms(key).length > 0) hits.add(lex.lang);
  return hits;
}
