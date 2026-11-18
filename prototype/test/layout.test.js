import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { LAYOUTS, MODE_PRESETS, layoutRowsTotal, makeNeighborFn, solveLayout } from '../src/layout.js';
import { LETTERS } from '../src/normalize.js';

const PARAMS = { minOpacity: 0.3, minWidth: 0.6, gain: 0.5, spaceGain: 0.8, maxDisplacement: 0.5 };

function dist(rByLetter, pEnd = 0) {
  const r = Object.fromEntries(LETTERS.map((c) => [c, rByLetter[c] ?? 0]));
  return { known: true, r, pEnd };
}

const SKEWED = dist({ e: 1, a: 0.6, i: 0.3, o: 0.1, n: 0.8, s: 0.5, t: 0.2, r: 0.4 }, 0.7);

function solve(layout, d, mode, { params, ...rest } = {}) {
  return solveLayout({ layout, dist: d, mode, params: { ...PARAMS, ...params }, ...rest });
}

describe('HappyPath', () => {
  it('les dispositions du §5.1 font 10 u par rangée', () => {
    for (const name of ['azerty', 'qwerty']) {
      for (const total of layoutRowsTotal(name)) assert.ok(Math.abs(total - 10) < 1e-9, `${name} : ${total}`);
    }
  });

  it('AZERTY : rangée 3 = ?123, 6 lettres à 0,85 u, espace 1,9 u, ↵, ⌫', () => {
    const row = LAYOUTS.azerty[2];
    assert.deepEqual(row.map((k) => k.id), ['sym', 'w', 'x', 'c', 'v', 'b', 'n', 'space', 'enter', 'back']);
    assert.equal(row[1].rest, 0.85);
    assert.equal(row[7].rest, 1.9);
  });

  it('QWERTY : ↵ en bout de rangée 2, rangée 3 de 7 lettres', () => {
    assert.equal(LAYOUTS.qwerty[1].at(-1).id, 'enter');
    assert.equal(LAYOUTS.qwerty[1][0].rest, 0.3);
    assert.equal(LAYOUTS.qwerty[2].filter((k) => k.kind === 'letter').length, 7);
    assert.equal(LAYOUTS.qwerty[2].find((k) => k.id === 'space').rest, 2.05);
  });

  it('la largeur totale de chaque rangée est conservée', () => {
    for (const mode of ['calm', 'strong', 'elastic']) {
      for (const name of ['azerty', 'qwerty']) {
        const out = solve(name, SKEWED, mode);
        for (const row of out.rows) {
          const total = row.reduce((a, k) => a + k.w, 0);
          assert.ok(Math.abs(total - 10) < 1e-9, `${name}/${mode} : ${total}`);
        }
      }
    }
  });

  it('élastique : une lettre probable est plus large et plus opaque qu’une improbable', () => {
    const out = solve('azerty', SKEWED, 'elastic');
    const e = out.keys.find((k) => k.id === 'e');
    const z = out.keys.find((k) => k.id === 'z');
    assert.ok(e.w > z.w);
    assert.ok(e.opacity > z.opacity);
    assert.ok(e.labelScale > z.labelScale);
    assert.ok(Math.abs(z.opacity - 0.3) < 1e-9);
  });

  it('calme : opacité seulement ; calme renforcé : opacité, libellé et largeur plafonnée', () => {
    const calm = solve('azerty', SKEWED, 'calm');
    assert.ok(calm.keys.every((k) => Math.abs(k.w - k.rest) < 1e-9 && k.labelScale === 1));
    assert.ok(calm.keys.some((k) => k.opacity < 1));
    const strong = solve('azerty', SKEWED, 'strong');
    assert.ok(strong.keys.some((k) => Math.abs(k.w - k.rest) > 1e-3));
    assert.ok(strong.keys.some((k) => k.labelScale < 1));
  });

  it('l’espace s’élargit avec P(fin)', () => {
    const wide = solve('azerty', dist({ e: 1 }, 1), 'elastic').keys.find((k) => k.id === 'space');
    const narrow = solve('azerty', dist({ e: 1 }, 0), 'elastic').keys.find((k) => k.id === 'space');
    assert.ok(wide.w > narrow.w);
  });

  it('au début de mot l’adaptation est atténuée de moitié', () => {
    const full = solve('azerty', SKEWED, 'elastic').keys.find((k) => k.id === 'z');
    const half = solve('azerty', SKEWED, 'elastic', { atWordStart: true }).keys.find((k) => k.id === 'z');
    assert.ok(Math.abs((1 - half.opacity) - (1 - full.opacity) / 2) < 1e-9);
  });
});

describe('ErrorCases', () => {
  it('rejette une disposition inconnue', () => {
    assert.throws(() => solve('dvorak', SKEWED, 'calm'), /Disposition inconnue/);
  });

  it('rejette un mode inconnu', () => {
    assert.throws(() => solve('azerty', SKEWED, 'turbo'), /Mode/);
  });

  it('distribution inconnue ou absente : clavier au repos', () => {
    for (const d of [null, { known: false, r: {}, pEnd: 0 }]) {
      const out = solve('azerty', d, 'elastic');
      assert.ok(out.keys.every((k) => k.w === k.rest && k.opacity === 1));
    }
  });

  it('mode désactivé : aucun effet', () => {
    const out = solve('qwerty', SKEWED, 'off');
    assert.ok(out.keys.every((k) => k.w === k.rest && k.opacity === 1 && k.labelScale === 1));
  });
});

describe('BoundaryValues', () => {
  it('invariant 2 : le centre ne s’éloigne jamais de maxDisplacement × largeur au repos', () => {
    for (const maxDisplacement of [0, 0.1, 0.5, 1]) {
      for (const mode of ['strong', 'elastic']) {
        for (const name of ['azerty', 'qwerty']) {
          const out = solve(name, SKEWED, mode, { params: { maxDisplacement } });
          const cap = maxDisplacement * MODE_PRESETS[mode].cap;
          for (const k of out.keys) {
            assert.ok(Math.abs(k.cx - k.rcx) <= cap * k.rest + 1e-9, `${name}/${mode}/${maxDisplacement}/${k.id}`);
          }
        }
      }
    }
  });

  it('maxDisplacement = 0 ramène tout au repos', () => {
    const out = solve('azerty', SKEWED, 'elastic', { params: { maxDisplacement: 0 } });
    assert.ok(out.keys.every((k) => Math.abs(k.w - k.rest) < 1e-9));
  });

  it('invariant 1 : topologie immuable (ordre, rangées, largeurs positives)', () => {
    for (const mode of ['off', 'calm', 'strong', 'elastic']) {
      const out = solve('qwerty', SKEWED, mode);
      out.rows.forEach((row, r) => {
        assert.deepEqual(row.map((k) => k.id), LAYOUTS.qwerty[r].map((k) => k.id));
        row.forEach((k, i) => {
          assert.ok(k.w > 0 && k.row === r);
          if (i > 0) assert.ok(Math.abs(row[i - 1].x + row[i - 1].w - k.x) < 1e-9);
        });
      });
    }
  });

  it('r = 0 pour toutes les lettres : opacité et largeur minimales sans effondrement', () => {
    const out = solve('azerty', dist({}), 'elastic');
    assert.ok(out.keys.filter((k) => k.kind === 'letter').every((k) => Math.abs(k.opacity - 0.3) < 1e-9 && k.w > 0));
  });

  it('voisinage des touches d’après les centres au repos', () => {
    const n = makeNeighborFn('azerty');
    assert.equal(n('a', 'z'), true);
    assert.equal(n('a', 'q'), true);
    assert.equal(n('a', 'p'), false);
    assert.equal(n('a', '?'), false);
  });
});
