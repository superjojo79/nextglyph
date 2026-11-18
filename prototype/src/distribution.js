// Distribution des continuations (SPEC §3, §5.2, §10.4, §10.6) et candidats.
// Mélange des lexiques actifs : P(c | p) = Σ_L π_L·mass_L(p·c) / Σ_L π_L·mass_L(p),
// c'est-à-dire π_FR·P_FR + π_EN·P_EN où chaque π_L est pondéré par la masse
// du préfixe dans L (a posteriori bayésien : un préfixe qui n'existe qu'en
// anglais pousse vers l'anglais).

import { LETTERS } from './normalize.js';

export const EDIT_PENALTY = {
  neighborSubstitution: 0.12,
  transposition: 0.12,
  substitution: 0.02,
  deletion: 0.04,
  insertion: 0.04,
};

const MIN_CORRECTION_LENGTH = 3;
const COMPLETION_CANDIDATES = 6;

function weights(lexicons, pi) {
  return lexicons.map((lex) => ({ lex, w: (pi[lex.lang] ?? 0) / (lex.total || 1) }));
}

/** Variantes à distance d'édition 1 de `key` : Map(variante -> pénalité). */
export function editVariants(key, isNeighbor = () => false) {
  const out = new Map();
  const put = (v, pen) => {
    if (v !== key && v !== '' && (out.get(v) ?? 0) < pen) out.set(v, pen);
  };
  for (let i = 0; i < key.length; i++) {
    put(key.slice(0, i) + key.slice(i + 1), EDIT_PENALTY.deletion);
    if (i + 1 < key.length && key[i] !== key[i + 1]) {
      put(key.slice(0, i) + key[i + 1] + key[i] + key.slice(i + 2), EDIT_PENALTY.transposition);
    }
    for (const c of LETTERS) {
      if (c === key[i]) continue;
      const pen = isNeighbor(key[i], c) ? EDIT_PENALTY.neighborSubstitution : EDIT_PENALTY.substitution;
      put(key.slice(0, i) + c + key.slice(i + 1), pen);
    }
  }
  for (let i = 0; i <= key.length; i++) {
    for (const c of LETTERS) put(key.slice(0, i) + c + key.slice(i), EDIT_PENALTY.insertion);
  }
  return out;
}

function neutral() {
  const p = {};
  const r = {};
  for (const c of LETTERS) {
    p[c] = 1 / 26;
    r[c] = 1;
  }
  return { known: false, corrected: false, p, r, pEnd: 0 };
}

function fromMasses(prefixNodes, lexicons, pi) {
  // prefixNodes : Array<{ weight, nodes: Array<node|null> }> (un nœud par lexique)
  const ws = weights(lexicons, pi);
  const acc = {};
  for (const c of LETTERS) acc[c] = 0;
  let total = 0;
  let end = 0;
  for (const { weight, nodes } of prefixNodes) {
    nodes.forEach((node, i) => {
      if (!node) return;
      const w = ws[i].w * weight;
      total += w * node.sum;
      end += w * node.endSum;
      for (const c of LETTERS) acc[c] += w * ws[i].lex.childSum(node, c);
    });
  }
  return { acc, total, end };
}

/**
 * Distribution de la prochaine lettre pour un préfixe (clé normalisée).
 * Préfixe inconnu : correction à distance 1, sinon état neutre (§5.2).
 */
export function letterDistribution(prefix, lexicons, pi, { isNeighbor } = {}) {
  const direct = fromMasses(
    [{ weight: 1, nodes: lexicons.map((l) => l.find(prefix)) }],
    lexicons,
    pi,
  );
  let acc = direct.acc;
  let total = direct.total;
  let end = direct.end;
  let corrected = false;
  if (total <= 0) {
    if (prefix.length < 2) return neutral();
    const groups = [];
    for (const [variant, weight] of editVariants(prefix, isNeighbor)) {
      const nodes = lexicons.map((l) => l.find(variant));
      if (nodes.some(Boolean)) groups.push({ weight, nodes });
    }
    const mixed = fromMasses(groups, lexicons, pi);
    if (mixed.total <= 0) return neutral();
    ({ acc, total, end } = mixed);
    corrected = true;
  }
  const p = {};
  const r = {};
  let max = 0;
  for (const c of LETTERS) {
    p[c] = acc[c] / total;
    if (p[c] > max) max = p[c];
  }
  for (const c of LETTERS) r[c] = max > 0 ? p[c] / max : 1; // aucune continuation : touches au repos, seul l'espace réagit
  return { known: true, corrected, p, r, pEnd: Math.min(1, end / total) };
}

/**
 * Candidats pour un mot en cours `t` (clé normalisée) : formes exactes,
 * complétions et corrections à distance 1 sur le mot complet.
 * `p` est la part du candidat dans la masse totale (utilisée au §5.5).
 * @returns {{list: Array<{text: string, p: number, score: number, kind: string}>, total: number}}
 */
export function candidates(t, lexicons, pi, { isNeighbor } = {}) {
  const ws = weights(lexicons, pi);
  const map = new Map();
  let total = 0;
  const add = (text, score, kind) => {
    const cur = map.get(text);
    if (cur) {
      cur.score += score;
      if (kind === 'exact') cur.kind = 'exact';
    } else {
      map.set(text, { text, score, kind });
    }
  };
  ws.forEach(({ lex, w }) => {
    if (w <= 0) return;
    const node = lex.find(t);
    if (node) {
      for (const form of node.forms ?? []) add(form.w, w * form.f, 'exact');
      total += w * node.sum;
      for (const form of lex.top(node, COMPLETION_CANDIDATES, { includeOwn: false })) {
        add(form.w, w * form.f, 'completion');
      }
    }
    if (t.length >= MIN_CORRECTION_LENGTH) {
      for (const [variant, pen] of editVariants(t, isNeighbor)) {
        const vnode = lex.find(variant);
        for (const form of vnode?.forms ?? []) {
          add(form.w, w * form.f * pen, 'correction');
          total += w * form.f * pen;
        }
      }
    }
  });
  const list = [...map.values()]
    .map((c) => ({ ...c, p: total > 0 ? Math.min(1, c.score / total) : 0 }))
    .sort((a, b) => b.score - a.score);
  return { list, total };
}
