// Gel de la disposition pendant le toucher (SPEC §2.3) et report de la mise
// à jour en saisie rapide (§5.2 « Saisie rapide »). Logique pure : le temps
// est fourni par l'appelant.

export class LayoutFreezer {
  constructor({ fastTypingMs = 120, initial = null } = {}) {
    this.fastTypingMs = fastTypingMs;
    this.displayed = initial;
    this.target = null;
    this.down = new Set();
    this.lastDown = -Infinity;
    this.lastUp = -Infinity;
    this.deferred = false;
  }

  /** Nouvelle disposition cible (calculée après une frappe). */
  setTarget(layout) {
    this.target = layout;
  }

  pointerDown(id, now) {
    if (now - this.lastDown < this.fastTypingMs) this.deferred = true;
    this.lastDown = now;
    this.down.add(id);
  }

  pointerUp(id, now) {
    if (this.down.delete(id)) this.lastUp = now;
  }

  /** Vrai si la cible peut être affichée maintenant. */
  ready(now) {
    if (this.target === null || this.down.size > 0) return false;
    return !this.deferred || now - this.lastUp >= this.fastTypingMs;
  }

  /** Applique la cible si possible ; retourne true si l'affichage a changé. */
  apply(now) {
    if (!this.ready(now)) return false;
    this.displayed = this.target;
    this.target = null;
    this.deferred = false;
    return true;
  }

  /** Délai (ms) avant le prochain essai, ou null si un doigt est posé / rien à faire. */
  nextDelay(now) {
    if (this.target === null || this.down.size > 0) return null;
    if (!this.deferred) return 0;
    return Math.max(0, this.fastTypingMs - (now - this.lastUp));
  }
}
