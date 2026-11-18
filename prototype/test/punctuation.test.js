import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  NBSP,
  NNBSP,
  PUNCT_BAR,
  applyDoubleSpace,
  applySign,
  isSentenceEnd,
  shouldCapitalize,
  spacingFor,
} from '../src/punctuation.js';

describe('HappyPath', () => {
  it('remplace l’espace automatique : « bonjour␣ » + « , » → « bonjour, »', () => {
    const r = applySign('bonjour ', ',', { autoSpace: true, french: true });
    assert.deepEqual(r, { text: 'bonjour, ', autoSpace: true });
  });

  it('typographie française : U+202F avant ; ! ? et U+00A0 avant :', () => {
    assert.equal(applySign('non ', '?', { autoSpace: true, french: true }).text, `non${NNBSP}? `);
    assert.equal(applySign('non ', ';', { autoSpace: true, french: true }).text, `non${NNBSP}; `);
    assert.equal(applySign('non ', '!', { autoSpace: true, french: true }).text, `non${NNBSP}! `);
    assert.equal(applySign('voici ', ':', { autoSpace: true, french: true }).text, `voici${NBSP}: `);
  });

  it('hors français : aucune espace avant', () => {
    assert.equal(applySign('no ', '?', { autoSpace: true, french: false }).text, 'no? ');
  });

  it('l’apostrophe ne crée pas de nouvelle espace automatique', () => {
    assert.deepEqual(applySign("l ", "'", { autoSpace: true, french: true }), { text: "l'", autoSpace: false });
  });

  it('tableau du §5.6 pour « et »', () => {
    assert.deepEqual(spacingFor('«', true), { before: '', after: NNBSP });
    assert.deepEqual(spacingFor('»', true), { before: NNBSP, after: ' ' });
    assert.deepEqual(spacingFor('…', true), { before: '', after: ' ' });
  });

  it('double espace : « mot␣ » devient « mot. »', () => {
    assert.equal(applyDoubleSpace('mot '), 'mot. ');
  });

  it('majuscule au début et après . ? ! … suivis d’espace', () => {
    assert.equal(shouldCapitalize(''), true);
    assert.equal(shouldCapitalize('Fini. '), true);
    assert.equal(shouldCapitalize(`Quoi${NNBSP}? `), true);
    assert.equal(shouldCapitalize('Alors… '), true);
    assert.equal(shouldCapitalize('ligne\n'), true);
    assert.equal(shouldCapitalize('Bonjour, '), false);
  });

  it('la barre de ponctuation propose , . ? ! : … ’', () => {
    assert.deepEqual(PUNCT_BAR, [',', '.', '?', '!', ':', '…', "'"]);
    assert.equal(isSentenceEnd('?'), true);
    assert.equal(isSentenceEnd(','), false);
  });
});

describe('ErrorCases', () => {
  it('refuse un signe vide', () => {
    assert.throws(() => applySign('a ', '', { autoSpace: true, french: true }), RangeError);
  });

  it('sans espace automatique, ne retire aucun caractère', () => {
    assert.equal(applySign('mot ', ',', { autoSpace: false, french: true }).text, 'mot , ');
  });

  it('double espace sans espace final : texte inchangé', () => {
    assert.equal(applyDoubleSpace('mot'), 'mot');
  });

  it('pas de majuscule après une virgule ou en milieu de mot', () => {
    assert.equal(shouldCapitalize('mot'), false);
    assert.equal(shouldCapitalize('a, '), false);
  });
});

describe('BoundaryValues', () => {
  it('texte vide + signe : pas d’espace insécable parasite', () => {
    assert.equal(applySign('', '?', { autoSpace: false, french: true }).text, '? ');
  });

  it('signe après une espace insécable existante : pas de doublon', () => {
    assert.equal(applySign(`a${NNBSP}`, '?', { autoSpace: false, french: true }).text, `a${NNBSP}? `);
  });

  it('une majuscule n’est demandée qu’après l’espace, pas juste après le point', () => {
    assert.equal(shouldCapitalize('Fin.'), false);
  });

  it('double espace sur texte réduit à une espace', () => {
    assert.equal(applyDoubleSpace(' '), '. ');
  });
});
