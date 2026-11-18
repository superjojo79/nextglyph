// Assemblage de la logique pure : lexiques, langue, éditeur, disposition cible.

import { Lexicon } from './lexicon.js';
import { LanguageTracker } from './language.js';
import { Editor } from './session.js';
import { DEFAULT_ROW_PITCH, makeNeighborFn, solveLayout } from './layout.js';

export class Engine {
  /**
   * @param {Record<string, Array<{w: string, f: number}>>} data  entrées par langue ('fr', 'en')
   * @param {object} settings  objet de réglages (modifié en direct par le panneau)
   */
  constructor(data, settings) {
    this.settings = settings;
    this.rowPitch = DEFAULT_ROW_PITCH;
    this.all = Object.entries(data).map(([lang, entries]) => new Lexicon(entries, { lang }));
    this.tracker = new LanguageTracker({ langs: this.#activeLangs(), preferred: settings['lang.preferred'] });
    this.neighborFns = {};
    this.editor = new Editor({
      lexicons: this.#activeLexicons(),
      tracker: this.tracker,
      settings,
      isNeighbor: (a, b) => this.neighbor()(a, b),
    });
    this.applySettings();
  }

  #activeLangs() {
    const available = this.all.map((l) => l.lang);
    const want = this.settings['lang.active'];
    const langs = want === 'both' ? available : available.filter((l) => l === want);
    return langs.length ? langs : available;
  }

  #activeLexicons() {
    const langs = this.#activeLangs();
    return this.all.filter((l) => langs.includes(l.lang));
  }

  neighbor() {
    const name = this.settings.layout;
    return (this.neighborFns[name] ??= makeNeighborFn(name, DEFAULT_ROW_PITCH));
  }

  /** À appeler après modification des réglages. */
  applySettings() {
    const s = this.settings;
    const langs = this.#activeLangs();
    this.editor.lexicons = this.#activeLexicons();
    this.tracker.langs = langs;
    this.tracker.preferred = langs.includes(s['lang.preferred']) ? s['lang.preferred'] : langs[0];
    this.tracker.window = s['lang.window'];
    this.tracker.lock = s['lang.lock'];
    while (this.tracker.words.length > this.tracker.window) this.tracker.words.shift();
  }

  /** Disposition cible d'après l'état courant de l'éditeur. */
  targetLayout() {
    const s = this.settings;
    const dist = this.editor.distribution();
    return {
      layout: solveLayout({
        layout: s.layout,
        dist,
        mode: s['adapt.mode'],
        atWordStart: this.editor.composing === '',
        rowPitch: this.rowPitch,
        params: {
          minOpacity: s['adapt.minOpacity'],
          minWidth: s['adapt.minWidth'],
          gain: s['adapt.gain'],
          spaceGain: s['adapt.spaceGain'],
          maxDisplacement: s['adapt.maxDisplacement'],
        },
      }),
      dist,
    };
  }
}
