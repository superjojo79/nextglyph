import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { SCHEMA, defaults, sanitize, sanitizeValue } from '../src/settings.js';

describe('HappyPath', () => {
  it('les valeurs par défaut sont celles de la spécification', () => {
    const d = defaults();
    assert.equal(d['adapt.mode'], 'strong'); // Calme renforcé
    assert.equal(d['adapt.minOpacity'], 0.3);
    assert.equal(d['adapt.minWidth'], 0.6);
    assert.equal(d['adapt.maxDisplacement'], 0.5);
    assert.equal(d['adapt.fastTypingMs'], 120);
    assert.equal(d['decode.alpha'], 0.5);
    assert.equal(d['decode.lambda'], 0.6);
    assert.equal(d['autocommit.minProb'], 0.7);
    assert.equal(d['autocommit.minRatio'], 2);
    assert.equal(d['autocommit.rejectLimit'], 2);
    assert.equal(d['lang.window'], 4);
  });

  it('chaque réglage du schéma a un défaut valide', () => {
    for (const s of SCHEMA) assert.equal(sanitizeValue(s.key, s.def), s.def, s.key);
  });

  it('fusionne des valeurs partielles avec les défauts', () => {
    const s = sanitize({ 'adapt.mode': 'elastic', 'adapt.gain': 0.9 });
    assert.equal(s['adapt.mode'], 'elastic');
    assert.equal(s['adapt.gain'], 0.9);
    assert.equal(s.layout, 'azerty');
  });
});

describe('ErrorCases', () => {
  it('une énumération invalide retombe sur le défaut', () => {
    assert.equal(sanitizeValue('adapt.mode', 'turbo'), 'strong');
  });

  it('un type invalide retombe sur le défaut', () => {
    assert.equal(sanitizeValue('adapt.gain', 'beaucoup'), 0.5);
    assert.equal(sanitizeValue('adapt.gain', Number.NaN), 0.5);
    assert.equal(sanitizeValue('ui.debug', 'oui'), false);
  });

  it('clés inconnues ignorées, entrée non objet tolérée', () => {
    assert.equal('inconnu' in sanitize({ inconnu: 1 }), false);
    assert.deepEqual(sanitize(null), defaults());
    assert.deepEqual(sanitize('x'), defaults());
    assert.equal(sanitizeValue('inconnu', 1), undefined);
  });
});

describe('BoundaryValues', () => {
  it('les nombres sont bornés au minimum et au maximum', () => {
    assert.equal(sanitizeValue('adapt.gain', -1), 0);
    assert.equal(sanitizeValue('adapt.gain', 1.5), 1.5);
    assert.equal(sanitizeValue('adapt.gain', 1.51), 1.5);
    assert.equal(sanitizeValue('adapt.minOpacity', 0.1), 0.1);
    assert.equal(sanitizeValue('adapt.minOpacity', 0.09), 0.1);
  });

  it('infini : valeur par défaut', () => {
    assert.equal(sanitizeValue('adapt.gain', Infinity), 0.5);
  });
});
