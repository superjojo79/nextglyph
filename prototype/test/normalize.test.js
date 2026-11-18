import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { capitalizeFirst, isKey, normalize, toKey } from '../src/normalize.js';

describe('HappyPath', () => {
  it('retire les accents et passe en minuscules', () => {
    assert.equal(normalize('École'), 'ecole');
    assert.equal(normalize('ÇA'), 'ca');
  });

  it('remplace œ et æ par oe et ae', () => {
    assert.equal(normalize('cœur'), 'coeur');
    assert.equal(normalize('Æon'), 'aeon');
  });

  it('la clé ne garde que les lettres a-z', () => {
    assert.equal(toKey("aujourd'hui"), 'aujourdhui');
    assert.equal(toKey('peut-être'), 'peutetre');
  });

  it('met la première lettre en majuscule', () => {
    assert.equal(capitalizeFirst('école'), 'École');
  });
});

describe('ErrorCases', () => {
  it('rejette une valeur qui n’est pas une chaîne', () => {
    assert.throws(() => normalize(42), TypeError);
    assert.throws(() => toKey(null), /chaîne/);
    assert.throws(() => capitalizeFirst(undefined), TypeError);
  });

  it('isKey refuse les non-clés sans lever d’exception', () => {
    assert.equal(isKey(12), false);
    assert.equal(isKey('a b'), false);
    assert.equal(isKey('É'), false);
  });
});

describe('BoundaryValues', () => {
  it('chaîne vide', () => {
    assert.equal(normalize(''), '');
    assert.equal(toKey(''), '');
    assert.equal(capitalizeFirst(''), '');
    assert.equal(isKey(''), false);
  });

  it('apostrophe typographique normalisée', () => {
    assert.equal(normalize('l’école'), "l'ecole");
  });

  it('un seul caractère et Unicode inhabituel', () => {
    assert.equal(toKey('é'), 'e');
    assert.equal(toKey('日本'), '');
    assert.equal(capitalizeFirst('\u{1F600}x'), '\u{1F600}x');
  });
});
