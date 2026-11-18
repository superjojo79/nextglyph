// Modèle de saisie : texte, mot en cours, espace automatique, annulation
// (SPEC §5.4 à §5.7). Aucune dépendance au DOM.

import { candidates, letterDistribution } from './distribution.js';
import { capitalizeFirst, toKey } from './normalize.js';
import {
  PUNCT_BAR,
  applyDoubleSpace,
  applySign,
  isSentenceEnd,
  shouldCapitalize,
} from './punctuation.js';
import { languageHits, validateWord } from './validation.js';

export class Editor {
  /**
   * @param {{lexicons: object[], tracker: object, settings: object, isNeighbor?: Function}} deps
   *   `settings` est lu à chaque appel (modifiable en direct).
   */
  constructor({ lexicons, tracker, settings, isNeighbor }) {
    if (!Array.isArray(lexicons) || lexicons.length === 0) {
      throw new RangeError('Au moins un lexique est requis.');
    }
    this.lexicons = lexicons;
    this.tracker = tracker;
    this.settings = settings;
    this.isNeighbor = isNeighbor;
    this.reset();
  }

  reset() {
    this.committed = '';
    this.composing = '';
    this.capAtStart = false;
    this.noAuto = false;
    this.autoSpace = false;
    this.lastAction = '';
    this.lastCommit = null;
    this.rejections = new Map();
  }

  get params() {
    const s = this.settings;
    return {
      enabled: s['autocommit.enabled'],
      minProb: s['autocommit.minProb'],
      minRatio: s['autocommit.minRatio'],
    };
  }

  #ctx(extra = {}) {
    return {
      lexicons: this.lexicons,
      pi: this.tracker.pi(),
      params: this.params,
      rejected: this.rejectedSet(),
      isNeighbor: this.isNeighbor,
      ...extra,
    };
  }

  rejectedSet() {
    const limit = this.settings['autocommit.rejectLimit'];
    return new Set([...this.rejections].filter(([, n]) => n >= limit).map(([k]) => k));
  }

  /** La prochaine lettre sera-t-elle une majuscule ? */
  capNext() {
    if (this.composing !== '') return false;
    return !!this.settings['caps.auto'] && shouldCapitalize(this.committed);
  }

  #cap(text) {
    return this.capAtStart ? capitalizeFirst(text) : text;
  }

  typeLetter(c) {
    if (this.composing === '') {
      this.capAtStart = !!this.settings['caps.auto'] && shouldCapitalize(this.committed);
      this.autoSpace = false;
      this.noAuto = false;
    }
    this.composing += c;
    this.lastCommit = null;
    this.lastAction = 'letter';
  }

  /** Valide le mot en cours (sans insérer d'espace) ; retourne la validation. */
  #commitComposing() {
    const key = this.composing;
    const v = validateWord(key, this.#ctx({ noAuto: this.noAuto }));
    const before = this.committed;
    const inserted = this.#cap(v.text);
    this.committed += inserted;
    this.composing = '';
    this.tracker.addWord(languageHits(v.text, this.lexicons));
    this.lastCommit = {
      before,
      typed: key,
      inserted,
      changed: v.changed,
      auto: v.kind === 'auto',
      word: v.text,
      cap: this.capAtStart,
    };
    this.noAuto = false;
    return v;
  }

  space() {
    if (this.composing !== '') {
      this.#commitComposing();
      this.committed += ' ';
      this.autoSpace = true;
      this.lastAction = 'commit';
    } else if (this.autoSpace && this.lastAction === 'commit' && this.settings['typo.doubleSpace']) {
      this.committed = applyDoubleSpace(this.committed);
      this.autoSpace = false;
      this.lastCommit = null;
      this.tracker.endSentence();
      this.lastAction = 'dspace';
    } else if (this.autoSpace && this.lastAction === 'sign') {
      // l'espace automatique est déjà là : rien à ajouter
    } else {
      this.committed += ' ';
      this.autoSpace = false;
      this.lastCommit = null;
      this.lastAction = 'space';
    }
  }

  /** Tap sur une suggestion : valide ce mot, espace automatique. */
  tapSuggestion(text) {
    const before = this.committed;
    const typed = this.composing;
    const inserted = this.#cap(text);
    this.committed += inserted + ' ';
    this.composing = '';
    this.tracker.addWord(languageHits(text, this.lexicons));
    this.lastCommit = { before, typed, inserted, changed: false, auto: false, word: text, cap: this.capAtStart };
    this.autoSpace = true;
    this.lastAction = 'commit';
    this.noAuto = false;
  }

  tapSign(sign) {
    if (this.composing !== '') {
      this.#commitComposing();
      this.committed += ' ';
      this.autoSpace = true;
    }
    const french = !!this.settings['typo.french'] && this.tracker.sentenceLang() === 'fr';
    const r = applySign(this.committed, sign, { autoSpace: this.autoSpace, french });
    this.committed = r.text;
    this.autoSpace = r.autoSpace;
    if (isSentenceEnd(sign)) this.tracker.endSentence();
    this.lastCommit = null;
    this.lastAction = 'sign';
  }

  enter() {
    if (this.composing !== '') this.#commitComposing();
    this.committed += '\n';
    this.autoSpace = false;
    this.lastCommit = null;
    this.lastAction = 'enter';
  }

  /** ⌫ ; retourne 'undo' si une insertion automatique a été annulée. */
  backspace() {
    if (this.composing !== '') {
      this.composing = this.composing.slice(0, -1);
      if (this.composing === '') this.noAuto = false;
      this.lastCommit = null;
      this.lastAction = 'back';
      return 'delete';
    }
    const lc = this.lastCommit;
    if (
      lc &&
      lc.changed &&
      this.lastAction === 'commit' &&
      this.committed === lc.before + lc.inserted + ' '
    ) {
      if (lc.auto) {
        const k = `${lc.typed}>${lc.word}`;
        this.rejections.set(k, (this.rejections.get(k) ?? 0) + 1);
      }
      this.committed = lc.before;
      this.composing = lc.typed;
      this.capAtStart = lc.cap;
      this.noAuto = true;
      this.autoSpace = false;
      this.lastCommit = null;
      this.lastAction = 'undo';
      return 'undo';
    }
    if (this.committed === '') return 'none';
    this.committed = this.committed.slice(0, -1);
    this.autoSpace = false;
    this.lastCommit = null;
    this.lastAction = 'back';
    const m = this.committed.match(/\p{L}+$/u);
    if (m) {
      this.committed = this.committed.slice(0, -m[0].length);
      this.composing = toKey(m[0]);
      this.capAtStart = m[0][0] !== m[0][0].toLowerCase();
      this.noAuto = true;
      if (this.composing === '') this.committed += m[0];
    }
    return 'delete';
  }

  /** Distribution de la prochaine lettre pour l'état courant. */
  distribution() {
    return letterDistribution(this.composing, this.lexicons, this.tracker.pi(), {
      isNeighbor: this.isNeighbor,
    });
  }

  /** Contenu de la barre de suggestions et de l'espace (§5.4, §5.5). */
  view() {
    const pi = this.tracker.pi();
    const lang = this.tracker.dominant();
    let bar;
    let preview = null;
    if (this.composing !== '') {
      const ctx = this.#ctx({ noAuto: this.noAuto });
      const v = validateWord(this.composing, ctx);
      if (v.kind === 'auto') preview = this.#cap(v.text);
      const list = candidates(this.composing, this.lexicons, pi, { isNeighbor: this.isNeighbor }).list;
      const ranked = [...list.filter((c) => c.kind === 'exact'), ...list.filter((c) => c.kind !== 'exact')];
      let best = ranked.map((c) => c.text);
      if (v.kind === 'auto') best = [v.text, ...best.filter((t) => t !== v.text)];
      if (best.length === 0) best = [this.composing];
      const [first, second, third] = best;
      const slot = (t) => (t === undefined ? null : { text: this.#cap(t), dominant: v.kind === 'auto' && t === v.text });
      bar = { mode: 'words', items: [slot(second), slot(first), slot(third)] };
    } else if (this.autoSpace && this.lastAction === 'commit') {
      bar = { mode: 'punct', items: PUNCT_BAR.map((text) => ({ text, dominant: false })) };
    } else {
      bar = { mode: 'empty', items: [] };
    }
    return {
      text: this.committed,
      composing: this.composing === '' ? '' : this.#cap(this.composing),
      bar,
      space: { lang, preview },
      capNext: this.capNext(),
      pi,
    };
  }
}
