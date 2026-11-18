// Lexique : trie sur les clés normalisées (SPEC §10.1).
// Chaque nœud stocke la somme des fréquences de son sous-arbre (`sum`), le
// maximum d'une forme du sous-arbre (`max`, pour l'exploration meilleur
// d'abord) et, à l'état final, les formes d'affichage (`forms`).
// P(c | p) et P(fin | p) se calculent donc en O(1) après la descente.

import { toKey } from './normalize.js';

function createNode() {
  return { ch: null, sum: 0, max: 0, endSum: 0, forms: null };
}

/** Une forme est « simple » si elle ne contient ni apostrophe ni trait d'union. */
export function isPlainForm(display) {
  return !/['’-]/.test(display);
}

class MaxHeap {
  constructor() {
    this.a = [];
  }
  get size() {
    return this.a.length;
  }
  push(item) {
    const a = this.a;
    a.push(item);
    let i = a.length - 1;
    while (i > 0) {
      const parent = (i - 1) >> 1;
      if (a[parent].p >= a[i].p) break;
      [a[parent], a[i]] = [a[i], a[parent]];
      i = parent;
    }
  }
  pop() {
    const a = this.a;
    const top = a[0];
    const last = a.pop();
    if (a.length > 0) {
      a[0] = last;
      let i = 0;
      for (;;) {
        const l = 2 * i + 1;
        const r = l + 1;
        let m = i;
        if (l < a.length && a[l].p > a[m].p) m = l;
        if (r < a.length && a[r].p > a[m].p) m = r;
        if (m === i) break;
        [a[m], a[i]] = [a[i], a[m]];
        i = m;
      }
    }
    return top;
  }
}

export class Lexicon {
  /**
   * @param {Array<{w: string, f: number}>} entries
   * @param {{lang?: string}} [options]
   */
  constructor(entries, { lang = '?' } = {}) {
    if (!Array.isArray(entries)) {
      throw new TypeError('Le lexique doit être un tableau [{"w": mot, "f": fréquence}].');
    }
    this.lang = lang;
    this.root = createNode();
    this.total = 0;
    this.size = 0;
    this.skipped = 0;
    for (const entry of entries) this.#add(entry);
  }

  #add(entry) {
    const w = entry?.w;
    const f = entry?.f;
    if (typeof w !== 'string' || w === '' || /\s/.test(w) || !Number.isFinite(f) || f <= 0) {
      this.skipped++;
      return;
    }
    const key = toKey(w);
    if (key === '') {
      this.skipped++;
      return;
    }
    let node = this.root;
    const path = [node];
    for (const c of key) {
      node.ch ??= Object.create(null);
      node = node.ch[c] ??= createNode();
      path.push(node);
    }
    node.forms ??= [];
    const existing = node.forms.find((x) => x.w === w);
    if (existing) {
      existing.f += f;
    } else {
      node.forms.push({ w, f, plain: isPlainForm(w) });
      this.size++;
    }
    node.forms.sort((x, y) => y.f - x.f);
    node.endSum += f;
    this.total += f;
    const best = existing ? existing.f : f;
    for (const n of path) {
      n.sum += f;
      if (best > n.max) n.max = best;
    }
  }

  /** Nœud du préfixe (clé normalisée), ou null. */
  find(key) {
    let node = this.root;
    for (const c of key) {
      node = node.ch?.[c];
      if (!node) return null;
    }
    return node;
  }

  /** Formes d'affichage dont la clé est exactement `key`. */
  forms(key) {
    return this.find(key)?.forms ?? [];
  }

  /** Vrai si `key` est un mot du lexique tel que tapé (au moins une forme simple). */
  hasPlainWord(key) {
    return this.forms(key).some((x) => x.plain);
  }

  /** Somme des fréquences du sous-arbre d'un nœud enfant, 0 si absent. */
  childSum(node, c) {
    return node?.ch?.[c]?.sum ?? 0;
  }

  /**
   * Meilleures formes sous un nœud (exploration meilleur d'abord, sans
   * parcourir tout le sous-arbre).
   * @returns {Array<{w: string, f: number}>}
   */
  top(node, n, { includeOwn = true } = {}) {
    const result = [];
    if (!node || n <= 0) return result;
    const heap = new MaxHeap();
    const pushNode = (nd, own) => {
      if (own && nd.forms) for (const form of nd.forms) heap.push({ p: form.f, form });
      if (nd.ch) for (const c in nd.ch) heap.push({ p: nd.ch[c].max, node: nd.ch[c] });
    };
    pushNode(node, includeOwn);
    while (heap.size > 0 && result.length < n) {
      const item = heap.pop();
      if (item.form) result.push(item.form);
      else pushNode(item.node, true);
    }
    return result;
  }
}
