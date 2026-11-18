// Journal local des frappes, exportable en JSON. Rien n'est envoyé sur le réseau.

export class TapLog {
  constructor({ max = 20000 } = {}) {
    if (!Number.isInteger(max) || max < 1) throw new RangeError('La taille maximale doit être un entier positif.');
    this.max = max;
    this.taps = [];
    this.startedAt = new Date().toISOString();
  }

  /**
   * @param {{t: number, x: number, y: number, hit: string|null, key: string, prefix: string, layout: string, mode: string}} tap
   *   x, y en unités (u) ; t en ms (horloge monotone de la page).
   */
  add(tap) {
    this.taps.push({ ...tap });
    if (this.taps.length > this.max) this.taps.shift();
  }

  clear() {
    this.taps = [];
    this.startedAt = new Date().toISOString();
  }

  get length() {
    return this.taps.length;
  }

  toJSON(settings = {}) {
    return {
      format: 'nextglyph-prototype-taps',
      version: 1,
      startedAt: this.startedAt,
      exportedAt: new Date().toISOString(),
      units: 'x : 10 u sur la largeur du clavier ; y : même unité, pas de rangée dans settings',
      settings,
      taps: this.taps,
    };
  }
}
