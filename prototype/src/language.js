// Score de langue (SPEC §10.6) : estimation π_FR / π_EN sur une fenêtre
// glissante des derniers mots validés. Un mot présent dans les deux lexiques
// compte pour les deux langues.

const PRIOR_WEIGHT = 1.5;
const PREFERRED_PRIOR = 0.7;

export class LanguageTracker {
  /**
   * @param {{langs?: string[], preferred?: string, window?: number, lock?: string}} [options]
   */
  constructor({ langs = ['fr', 'en'], preferred = 'fr', window = 4, lock = 'auto' } = {}) {
    if (!Array.isArray(langs) || langs.length === 0) {
      throw new RangeError('Au moins une langue active est requise.');
    }
    if (!langs.includes(preferred)) preferred = langs[0];
    if (!Number.isInteger(window) || window < 1) {
      throw new RangeError('La fenêtre doit être un entier positif.');
    }
    this.langs = [...langs];
    this.preferred = preferred;
    this.window = window;
    this.lock = lock;
    this.words = []; // { hits: Set<lang>, sentence: number }
    this.sentence = 0;
  }

  /** Enregistre un mot validé ; `hits` : langues dont le lexique contient le mot. */
  addWord(hits) {
    const set = new Set([...hits].filter((l) => this.langs.includes(l)));
    this.words.push({ hits: set, sentence: this.sentence });
    if (this.words.length > this.window) this.words.shift();
  }

  /** Une ponctuation finale a été insérée : nouvelle phrase. */
  endSentence() {
    this.sentence++;
  }

  #prior(lang) {
    if (this.langs.length === 1) return 1;
    return lang === this.preferred ? PREFERRED_PRIOR : (1 - PREFERRED_PRIOR) / (this.langs.length - 1);
  }

  /** Probabilités de langue { fr: x, en: y }, somme 1. */
  pi() {
    if (this.lock !== 'auto' && this.langs.includes(this.lock)) {
      return Object.fromEntries(this.langs.map((l) => [l, l === this.lock ? 1 : 0]));
    }
    const raw = {};
    for (const l of this.langs) raw[l] = this.#prior(l) * PRIOR_WEIGHT;
    for (const w of this.words) for (const l of w.hits) raw[l] += 1;
    const sum = Object.values(raw).reduce((a, b) => a + b, 0);
    return Object.fromEntries(this.langs.map((l) => [l, raw[l] / sum]));
  }

  #argmax(scores) {
    let best = this.preferred;
    let bestScore = -1;
    for (const l of this.langs) {
      if (scores[l] > bestScore + 1e-12) {
        best = l;
        bestScore = scores[l];
      }
    }
    return best;
  }

  /** Langue dominante de l'estimation globale (indicateur sur l'espace). */
  dominant() {
    return this.#argmax(this.pi());
  }

  /** Langue dominante depuis la dernière ponctuation finale. */
  sentenceLang() {
    const pi = this.pi();
    const counts = Object.fromEntries(this.langs.map((l) => [l, 0]));
    for (const w of this.words) {
      if (w.sentence !== this.sentence) continue;
      for (const l of w.hits) counts[l]++;
    }
    const max = Math.max(...Object.values(counts));
    const leaders = this.langs.filter((l) => counts[l] === max);
    if (max === 0 || leaders.length !== 1 || this.lock !== 'auto') return this.#argmax(pi);
    return leaders[0];
  }
}
