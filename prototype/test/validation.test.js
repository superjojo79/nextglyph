import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { Lexicon } from '../src/lexicon.js';
import { languageHits, validateWord } from '../src/validation.js';
import { MINI_EN, MINI_FR } from '../testkit/fixtures.js';

const fr = new Lexicon(MINI_FR, { lang: 'fr' });
const en = new Lexicon(MINI_EN, { lang: 'en' });
const P = { enabled: true, minProb: 0.6, minRatio: 2 };
const ctx = (extra = {}) => ({ lexicons: [fr, en], pi: { fr: 0.5, en: 0.5 }, params: P, ...extra });

describe('HappyPath', () => {
  it('cas 1 : un mot du lexique est inséré avec ses accents si sans ambiguïté', () => {
    const v = validateWord('ecole', ctx());
    assert.deepEqual([v.kind, v.text, v.changed], ['word', 'école', true]);
  });

  it('cas 2 : complétion (bonj → bonjour)', () => {
    const v = validateWord('bonj', ctx());
    assert.equal(v.kind, 'auto');
    assert.equal(v.text, 'bonjour');
  });

  it('cas 2 : correction (bonjoru → bonjour)', () => {
    assert.equal(validateWord('bonjoru', ctx()).text, 'bonjour');
  });

  it('cas 2 : élision, trait d’union, contraction anglaise', () => {
    assert.equal(validateWord('jai', ctx()).text, "j'ai");
    assert.equal(validateWord('peutetre', ctx()).text, 'peut-être');
    assert.equal(validateWord('dont', ctx()).text, "don't");
  });

  it('cas 3 : mot inconnu inséré tel quel', () => {
    const v = validateWord('zzqx', ctx());
    assert.deepEqual([v.kind, v.text, v.changed], ['raw', 'zzqx', false]);
  });

  it('le score de langue alimente les langues connues du mot', () => {
    assert.deepEqual([...languageHits('école', [fr, en])], ['fr']);
    assert.deepEqual([...languageHits('table', [fr, en])].sort(), ['en', 'fr']);
    assert.deepEqual([...languageHits("don't", [fr, en])], ['en']);
  });
});

describe('ErrorCases', () => {
  it('cas ambigus (a/à) : insérés tels quels', () => {
    assert.equal(validateWord('a', ctx()).text, 'a');
  });

  it('plusieurs accentuations possibles (pêche/péché) : tel quel', () => {
    const v = validateWord('peche', ctx());
    assert.equal(v.kind, 'word');
    assert.equal(v.text, 'peche');
  });

  it('jamais de remplacement automatique pour un mot valide (well, pas we’ll)', () => {
    const v = validateWord('well', ctx());
    assert.equal(v.kind, 'word');
    assert.equal(v.text, 'well');
  });

  it('forme sans accent valide en anglais : jamais accentuée (cafe)', () => {
    assert.equal(validateWord('cafe', ctx()).text, 'cafe');
  });

  it('validation par espace désactivée, annulation apprise ou noAuto : pas de remplacement', () => {
    assert.equal(validateWord('bonj', ctx({ params: { ...P, enabled: false } })).kind, 'raw');
    assert.equal(validateWord('bonj', ctx({ rejected: new Set(['bonj>bonjour']) })).kind, 'raw');
    assert.equal(validateWord('bonj', ctx({ noAuto: true })).kind, 'raw');
  });

  it('pas de candidat dominant quand deux candidats se ressemblent', () => {
    // « bon » + s : bonjour (1000) et bonsoir (300) sont tous deux possibles pour « bon »
    const lex = new Lexicon([{ w: 'bonjour', f: 100 }, { w: 'bonsoir', f: 90 }], { lang: 'fr' });
    const v = validateWord('bon', { lexicons: [lex], pi: { fr: 1 }, params: P });
    assert.equal(v.kind, 'raw');
  });
});

describe('BoundaryValues', () => {
  it('mot vide', () => {
    assert.equal(validateWord('', ctx()).text, '');
  });

  it('seuil de probabilité : exactement minProb accepté, au-dessus de minProb refusé', () => {
    const lex = new Lexicon([{ w: 'bonjour', f: 100 }], { lang: 'fr' });
    const base = { lexicons: [lex], pi: { fr: 1 } };
    assert.equal(validateWord('bonj', { ...base, params: { ...P, minProb: 1 } }).kind, 'auto');
    const lex2 = new Lexicon([{ w: 'bonjour', f: 100 }, { w: 'bonjoura', f: 1 }], { lang: 'fr' });
    assert.equal(validateWord('bonj', { lexicons: [lex2], pi: { fr: 1 }, params: { ...P, minProb: 0.995 } }).kind, 'raw');
  });

  it('seuil de ratio : égal accepté, supérieur refusé', () => {
    const lex = new Lexicon([{ w: 'bonjour', f: 200 }, { w: 'bonsoir', f: 100 }], { lang: 'fr' });
    const run = (minRatio) =>
      validateWord('bon', { lexicons: [lex], pi: { fr: 1 }, params: { enabled: true, minProb: 0.1, minRatio } });
    assert.equal(run(2).kind, 'auto');
    assert.equal(run(2.01).kind, 'raw');
  });

  it('une capitale du lexique est conservée (i → I)', () => {
    assert.equal(validateWord('i', ctx()).text, 'I');
  });
});
