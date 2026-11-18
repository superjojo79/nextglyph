import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { Lexicon } from '../src/lexicon.js';
import { candidates, editVariants, letterDistribution } from '../src/distribution.js';
import { LETTERS } from '../src/normalize.js';
import { MINI_EN, MINI_FR } from '../testkit/fixtures.js';

const fr = new Lexicon(MINI_FR, { lang: 'fr' });
const en = new Lexicon(MINI_EN, { lang: 'en' });
const PI_FR = { fr: 1, en: 0 };
const PI_BOTH = { fr: 0.5, en: 0.5 };

describe('HappyPath', () => {
  it('P(c | p) : seule la continuation existante a un poids', () => {
    const d = letterDistribution('bonj', [fr], PI_FR);
    assert.equal(d.known, true);
    assert.equal(d.r.o, 1);
    assert.equal(d.r.z, 0);
  });

  it('r(c) est relatif à la meilleure continuation', () => {
    const d = letterDistribution('bons', [fr], PI_FR);
    assert.equal(d.r.o, 1);
    const d2 = letterDistribution('bon', [fr], PI_FR);
    assert.ok(d2.r.s < 1 && d2.r.s > 0);
    assert.ok(Math.abs(d2.r.s - 300 / 1000) < 1e-9);
  });

  it('P(fin | p) vaut la part des mots complets', () => {
    const d = letterDistribution('bonjour', [fr], PI_FR);
    assert.equal(d.pEnd, 1);
    const d2 = letterDistribution('bonjo', [fr], PI_FR);
    assert.equal(d2.pEnd, 0);
  });

  it('mélange bilingue selon π', () => {
    const onlyEn = letterDistribution('he', [fr, en], { fr: 0, en: 1 });
    assert.ok(onlyEn.r.l > 0);
    const mix = letterDistribution('', [fr, en], PI_BOTH);
    assert.ok(mix.p.b > 0 && mix.p.h > 0);
    const sum = LETTERS.reduce((a, c) => a + mix.p[c], 0);
    assert.ok(Math.abs(sum - 1) < 1e-9);
  });

  it('candidats : complétion et correction par transposition', () => {
    assert.equal(candidates('bonj', [fr], PI_FR).list[0].text, 'bonjour');
    const fix = candidates('bonjoru', [fr], PI_FR).list[0];
    assert.equal(fix.text, 'bonjour');
    assert.equal(fix.kind, 'correction');
  });

  it('préfixe inconnu : correction à distance 1 sur le préfixe', () => {
    const d = letterDistribution('bnoj', [fr], PI_FR);
    assert.equal(d.known, true);
    assert.equal(d.corrected, true);
    assert.ok(d.r.o > 0);
  });
});

describe('ErrorCases', () => {
  it('préfixe inconnu sans correction : clavier neutre', () => {
    const d = letterDistribution('qqqqq', [fr], PI_FR);
    assert.equal(d.known, false);
    assert.ok(LETTERS.every((c) => d.r[c] === 1));
    assert.equal(d.pEnd, 0);
  });

  it('aucun candidat pour une suite impossible', () => {
    const r = candidates('qqqqq', [fr], PI_FR);
    assert.deepEqual(r.list, []);
    assert.equal(r.total, 0);
  });

  it('une langue de poids nul ne contribue pas', () => {
    const d = letterDistribution('hel', [fr, en], PI_FR);
    assert.equal(d.known, false);
  });
});

describe('BoundaryValues', () => {
  it('préfixe vide : distribution des initiales, pEnd nul', () => {
    const d = letterDistribution('', [fr], PI_FR);
    assert.equal(d.known, true);
    assert.equal(d.pEnd, 0);
    assert.ok(d.r.a > 0);
  });

  it('préfixe d’une lettre inconnu : pas de correction (longueur minimale), neutre', () => {
    const d = letterDistribution('x', [fr], PI_FR);
    assert.equal(d.known, false);
  });

  it('mot complet sans continuation : touches au repos, espace sollicité', () => {
    const d = letterDistribution('bonjour', [fr], PI_FR);
    assert.ok(LETTERS.every((c) => d.r[c] === 1));
    assert.equal(d.pEnd, 1);
  });

  it('pas de corrections sous 3 lettres', () => {
    const list = candidates('bn', [fr], PI_FR).list;
    assert.ok(list.every((c) => c.kind !== 'correction'));
  });

  it('variantes d’édition : suppression, transposition, substitution, insertion', () => {
    const v = editVariants('ab', (a, b) => a === 'b' && b === 'c');
    assert.equal(v.has('a'), true);
    assert.equal(v.has('ba'), true);
    assert.equal(v.has('ac'), true);
    assert.equal(v.has('abc'), true);
    assert.ok(v.get('ac') > v.get('az'));
    assert.equal(v.has('ab'), false);
  });
});
