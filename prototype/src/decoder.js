// Décodage du tap (SPEC §5.3) :
//   score(c) = P(point | c) · P(c | p)^λ
//   P(point | c) = α·N(point ; centre_affiché(c), σ) + (1 − α)·N(point ; centre_repos(c), σ)
// Les coordonnées sont en unités (u) : x sur 10 u de large, y avec le pas de rangée.

const FLOOR = 0.15; // toute lettre reste tapable (§2.4)

function gauss(dx, dy, sigma) {
  return Math.exp(-(dx * dx + dy * dy) / (2 * sigma * sigma)) / (2 * Math.PI * sigma * sigma);
}

/**
 * @param {{x: number, y: number}} point
 * @param {Array<{id: string, cx: number, cy: number, rcx: number}>} keys  touches lettres (cy = centre vertical)
 * @param {Record<string, number>|null} prob  P(c | p) ; null = pas de modèle de langue
 * @param {{alpha: number, lambda: number, sigma: number}} params
 * @returns {{letter: string, scores: Array<{id: string, score: number}>}}
 */
export function decodeTap(point, keys, prob, { alpha, lambda, sigma }) {
  if (!keys.length) throw new RangeError('Aucune touche lettre à décoder.');
  if (!(sigma > 0)) throw new RangeError('σ doit être strictement positif.');
  const scores = keys.map((k) => {
    const spatial =
      alpha * gauss(point.x - k.cx, point.y - k.cy, sigma) +
      (1 - alpha) * gauss(point.x - k.rcx, point.y - k.cy, sigma);
    const p = prob ? (1 - FLOOR) * (prob[k.id] ?? 0) + FLOOR / 26 : 1;
    return { id: k.id, score: spatial * (lambda === 0 ? 1 : Math.pow(p, lambda)) };
  });
  scores.sort((a, b) => b.score - a.score);
  return { letter: scores[0].id, scores };
}

/** Touche (tout type) dont le rectangle affiché contient le point ; null si hors clavier. */
export function hitTest(point, layout) {
  const { rows, rowPitch } = layout;
  const row = Math.floor(point.y / rowPitch);
  if (row < 0 || row >= rows.length || point.x < 0) return null;
  let found = null;
  for (const k of rows[row]) {
    if (point.x >= k.x && point.x < k.x + k.w) found = k;
  }
  if (!found && rows[row].length) {
    const last = rows[row][rows[row].length - 1];
    if (point.x >= last.x + last.w && point.x <= last.x + last.w + 1e-9) found = last;
  }
  return found?.kind === 'spacer' ? null : found;
}
