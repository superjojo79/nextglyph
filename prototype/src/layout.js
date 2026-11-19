// Dispositions (SPEC §5.1) et solveur de disposition adaptative (SPEC §5.2).
// Topologie immuable : l'ordre des touches et leur rangée ne changent jamais ;
// seules varient la largeur, l'opacité et la taille du libellé.

const letters = (s, rest = 1) => s.split('').map((id) => ({ id, kind: 'letter', rest }));
const fn = (id, rest) => ({ id, kind: 'fn', rest });

export const LAYOUTS = {
  azerty: [
    letters('azertyuiop'),
    letters('qsdfghjklm'),
    [fn('sym', 1), ...letters('wxcvbn', 0.9), { id: 'space', kind: 'space', rest: 1.6 }, fn('enter', 1), fn('back', 1)],
  ],
  qwerty: [
    letters('qwertyuiop'),
    [{ id: 'spacer', kind: 'spacer', rest: 0.3 }, ...letters('asdfghjkl', 0.95), fn('enter', 1.15)],
    [fn('sym', 1), ...letters('zxcvbnm', 0.9), { id: 'space', kind: 'space', rest: 1.7 }, fn('back', 1)],
  ],
};

export const ROW_UNITS = 10;
export const DEFAULT_ROW_PITCH = 1.2; // pas vertical entre rangées, en unités (u)

/** Présets des modes d'adaptation (SPEC §5.2, tableau des modes). */
export const MODE_PRESETS = {
  off: { opacity: 0, label: 0, width: 0, cap: 0 },
  calm: { opacity: 0.5, label: 0, width: 0, cap: 0 },
  strong: { opacity: 0.8, label: 0.15, width: 0.4, cap: 0.4 },
  elastic: { opacity: 1, label: 0.3, width: 1, cap: 1 },
};

export function layoutRowsTotal(name) {
  return LAYOUTS[name].map((row) => row.reduce((a, k) => a + k.rest, 0));
}

function restGeometry(rows, rowPitch) {
  return rows.map((row, r) => {
    let x = 0;
    return row.map((k) => {
      const out = { ...k, row: r, rx: x, cy: (r + 0.5) * rowPitch };
      x += k.rest;
      return out;
    });
  });
}

/** Fonction « touches voisines » d'après les centres au repos. */
export function makeNeighborFn(name, rowPitch = DEFAULT_ROW_PITCH) {
  const centers = new Map();
  for (const row of restGeometry(LAYOUTS[name], rowPitch)) {
    for (const k of row) if (k.kind === 'letter') centers.set(k.id, { x: k.rx + k.rest / 2, y: k.cy });
  }
  return (a, b) => {
    const pa = centers.get(a);
    const pb = centers.get(b);
    if (!pa || !pb) return false;
    return Math.hypot(pa.x - pb.x, pa.y - pb.y) < 1.5;
  };
}

/**
 * Calcule la disposition affichée.
 * @param {object} o
 * @param {'azerty'|'qwerty'} o.layout
 * @param {{known: boolean, r: Record<string, number>, pEnd: number}|null} o.dist
 * @param {'off'|'calm'|'strong'|'elastic'} o.mode
 * @param {object} o.params  adapt.minOpacity, minWidth, gain, spaceGain, maxDisplacement
 * @param {boolean} [o.atWordStart]  adaptation atténuée de moitié
 * @param {number} [o.rowPitch]
 */
export function solveLayout({ layout, dist, mode, params, atWordStart = false, rowPitch = DEFAULT_ROW_PITCH }) {
  const def = LAYOUTS[layout];
  if (!def) throw new RangeError(`Disposition inconnue : ${layout}`);
  const preset = MODE_PRESETS[mode];
  if (!preset) throw new RangeError(`Mode d'adaptation inconnu : ${mode}`);
  const { minOpacity, minWidth, gain, spaceGain, maxDisplacement } = params;
  const active = !!dist && dist.known && mode !== 'off';
  const iota = atWordStart ? 0.5 : 1;
  const geo = restGeometry(def, rowPitch);
  const keys = [];
  const rows = geo.map((row) => {
    const group = row.filter((k) => k.kind === 'letter' || k.kind === 'space');
    const groupTotal = group.reduce((a, k) => a + k.rest, 0);
    const widths = new Map(row.map((k) => [k.id, k.rest]));
    const info = new Map();
    if (active) {
      const space = row.find((k) => k.kind === 'space');
      let spaceW = space ? space.rest : 0;
      if (space) {
        spaceW = Math.min(0.6 * groupTotal, space.rest + preset.width * iota * spaceGain * dist.pEnd);
        widths.set('space', spaceW);
      }
      const raw = new Map();
      let rawSum = 0;
      for (const k of group) {
        if (k.kind !== 'letter') continue;
        const rc = dist.r[k.id] ?? 0;
        const s = Math.sqrt(rc);
        const base = rc === 0 ? minWidth : 0.8 + gain * s;
        const w = (1 + preset.width * iota * (base - 1)) * k.rest;
        raw.set(k.id, w);
        rawSum += w;
        const opBase = rc === 0 ? minOpacity : 0.55 + 0.45 * s;
        info.set(k.id, {
          r: rc,
          opacity: 1 - preset.opacity * iota * (1 - opBase),
          labelScale: 1 - preset.label * iota * (1 - s),
        });
      }
      const scale = rawSum > 0 ? (groupTotal - spaceW) / rawSum : 1;
      for (const [id, w] of raw) widths.set(id, w * scale);
    }
    // Plafond de déplacement des centres (§2.2) : mélange linéaire vers le repos.
    let t = 1;
    if (active) {
      let x = 0;
      for (const k of row) {
        const w = widths.get(k.id);
        const d = Math.abs(x + w / 2 - (k.rx + k.rest / 2));
        const cap = maxDisplacement * preset.cap * k.rest;
        if (d > 1e-9 && d > cap) t = Math.min(t, cap / d);
        x += w;
      }
    }
    let x = 0;
    return row.map((k) => {
      const w = k.rest + t * (widths.get(k.id) - k.rest);
      const i = info.get(k.id);
      const out = {
        id: k.id,
        kind: k.kind,
        row: k.row,
        x,
        w,
        cx: x + w / 2,
        cy: k.cy,
        rcx: k.rx + k.rest / 2,
        rest: k.rest,
        r: i ? i.r : 1,
        opacity: i ? i.opacity : 1,
        labelScale: i ? i.labelScale : 1,
      };
      x += w;
      keys.push(out);
      return out;
    });
  });
  return { layout, rows, keys, rowPitch, totalRows: rows.length, active };
}
