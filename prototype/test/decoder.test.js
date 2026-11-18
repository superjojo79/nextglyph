import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { decodeTap, hitTest } from '../src/decoder.js';
import { solveLayout } from '../src/layout.js';
import { LETTERS } from '../src/normalize.js';

const PARAMS = { alpha: 0.5, lambda: 0.6, sigma: 0.3 };
const SOLVE_PARAMS = { minOpacity: 0.3, minWidth: 0.6, gain: 0.5, spaceGain: 0.8, maxDisplacement: 0.5 };

const rest = solveLayout({ layout: 'azerty', dist: null, mode: 'off', params: SOLVE_PARAMS });
const letters = rest.keys.filter((k) => k.kind === 'letter');
const centerOf = (id) => letters.find((k) => k.id === id);

function probOnly(...ids) {
  const p = Object.fromEntries(LETTERS.map((c) => [c, 0]));
  for (const id of ids) p[id] = 1 / ids.length;
  return p;
}

describe('HappyPath', () => {
  it('sans modèle de langue, la touche la plus proche gagne', () => {
    const z = centerOf('z');
    assert.equal(decodeTap({ x: z.cx, y: z.cy }, letters, null, PARAMS).letter, 'z');
  });

  it('un tap entre deux touches va à la lettre la plus probable', () => {
    const a = centerOf('a');
    const z = centerOf('z');
    const point = { x: (a.cx + z.cx) / 2 - 0.02, y: a.cy }; // un peu côté « a »
    const toZ = decodeTap(point, letters, probOnly('z'), PARAMS).letter;
    assert.equal(toZ, 'z');
    assert.equal(decodeTap(point, letters, probOnly('a'), PARAMS).letter, 'a');
  });

  it('un tap franchement au centre d’une touche improbable produit cette lettre (λ = 0,6)', () => {
    const q = centerOf('q');
    assert.equal(decodeTap({ x: q.cx, y: q.cy }, letters, probOnly('s'), PARAMS).letter, 'q');
  });

  it('mélange affiché / repos : une touche déplacée attire aussi à sa position de repos', () => {
    const e = { ...centerOf('e'), cx: centerOf('e').cx + 1 }; // affichée 1 u plus à droite
    const rIdx = letters.map((k) => (k.id === 'e' ? e : k));
    const atRest = { x: centerOf('e').rcx, y: e.cy };
    const withMix = decodeTap(atRest, rIdx, null, { ...PARAMS, alpha: 0.5 }).scores.find((s) => s.id === 'e').score;
    const displayedOnly = decodeTap(atRest, rIdx, null, { ...PARAMS, alpha: 1 }).scores.find((s) => s.id === 'e').score;
    assert.ok(withMix > displayedOnly);
  });

  it('hitTest retrouve la touche affichée', () => {
    const k = centerOf('p');
    assert.equal(hitTest({ x: k.cx, y: k.cy }, rest).id, 'p');
    const sp = rest.keys.find((x) => x.id === 'space');
    assert.equal(hitTest({ x: sp.cx, y: sp.cy }, rest).kind, 'space');
  });
});

describe('ErrorCases', () => {
  it('refuse une liste de touches vide', () => {
    assert.throws(() => decodeTap({ x: 0, y: 0 }, [], null, PARAMS), /Aucune touche/);
  });

  it('refuse σ ≤ 0', () => {
    assert.throws(() => decodeTap({ x: 0, y: 0 }, letters, null, { ...PARAMS, sigma: 0 }), RangeError);
  });

  it('hors du clavier : pas de touche', () => {
    assert.equal(hitTest({ x: -1, y: 0.5 }, rest), null);
    assert.equal(hitTest({ x: 5, y: -0.1 }, rest), null);
    assert.equal(hitTest({ x: 5, y: 99 }, rest), null);
  });
});

describe('BoundaryValues', () => {
  it('λ = 0 : décodage purement spatial', () => {
    const q = centerOf('q');
    const near = { x: q.cx + 0.3, y: q.cy };
    const r = decodeTap(near, letters, probOnly('s'), { ...PARAMS, lambda: 0 });
    assert.equal(r.letter, 'q');
  });

  it('toute lettre reste tapable même avec P = 0', () => {
    const x = centerOf('x');
    assert.equal(decodeTap({ x: x.cx, y: x.cy }, letters, probOnly('e'), PARAMS).letter, 'x');
  });

  it('bord exact entre deux touches : une touche est choisie sans erreur', () => {
    const a = centerOf('a');
    const z = centerOf('z');
    const r = decodeTap({ x: a.x + a.w, y: a.cy }, letters, null, PARAMS);
    assert.ok(['a', 'z'].includes(r.letter));
    assert.equal(hitTest({ x: a.x + a.w, y: a.cy }, rest).id, z.id);
  });
});
