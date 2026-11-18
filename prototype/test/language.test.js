import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { LanguageTracker } from '../src/language.js';

const sum = (pi) => Object.values(pi).reduce((a, b) => a + b, 0);

describe('HappyPath', () => {
  it('part de la langue préférée', () => {
    const t = new LanguageTracker({ preferred: 'en' });
    assert.equal(t.dominant(), 'en');
    assert.ok(Math.abs(sum(t.pi()) - 1) < 1e-9);
  });

  it('bascule progressivement vers la langue des mots validés', () => {
    const t = new LanguageTracker({ preferred: 'fr' });
    const start = t.pi().en;
    t.addWord(['en']);
    const one = t.pi().en;
    for (let i = 0; i < 4; i++) t.addWord(['en']);
    assert.ok(start < one && one < t.pi().en);
    assert.equal(t.dominant(), 'en');
  });

  it('un mot commun aux deux langues compte pour les deux', () => {
    const t = new LanguageTracker({ preferred: 'fr' });
    const before = t.pi();
    t.addWord(['fr', 'en']);
    const after = t.pi();
    assert.ok(after.en > before.en);
  });

  it('la langue de la phrase repart de zéro après une ponctuation finale', () => {
    const t = new LanguageTracker({ preferred: 'fr' });
    t.addWord(['en']);
    t.addWord(['en']);
    assert.equal(t.sentenceLang(), 'en');
    t.endSentence();
    t.addWord(['fr']);
    assert.equal(t.sentenceLang(), 'fr');
  });
});

describe('ErrorCases', () => {
  it('refuse une liste de langues vide', () => {
    assert.throws(() => new LanguageTracker({ langs: [] }), RangeError);
  });

  it('refuse une fenêtre invalide', () => {
    assert.throws(() => new LanguageTracker({ window: 0 }), /fenêtre/);
    assert.throws(() => new LanguageTracker({ window: 2.5 }), RangeError);
  });

  it('ignore les langues inactives et les mots inconnus', () => {
    const t = new LanguageTracker({ langs: ['fr'], preferred: 'en' });
    t.addWord(['en']);
    assert.deepEqual(t.pi(), { fr: 1 });
    assert.equal(t.preferred, 'fr');
  });
});

describe('BoundaryValues', () => {
  it('la fenêtre glissante oublie les mots anciens (8 puis 9)', () => {
    const t = new LanguageTracker({ window: 8 });
    for (let i = 0; i < 8; i++) t.addWord(['en']);
    assert.equal(t.words.length, 8);
    for (let i = 0; i < 9; i++) t.addWord(['fr']);
    assert.equal(t.words.length, 8);
    assert.equal(t.dominant(), 'fr');
  });

  it('verrouillage : la langue verrouillée vaut 1', () => {
    const t = new LanguageTracker({ lock: 'en' });
    t.addWord(['fr']);
    assert.deepEqual(t.pi(), { fr: 0, en: 1 });
    assert.equal(t.sentenceLang(), 'en');
  });

  it('phrase vide : retombe sur la langue globale', () => {
    const t = new LanguageTracker({ preferred: 'en' });
    assert.equal(t.sentenceLang(), 'en');
  });
});
