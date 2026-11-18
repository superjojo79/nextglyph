import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { TapLog } from '../src/log.js';

const tap = (i) => ({ t: i, x: 1, y: 2, hit: 'a', key: 'z', prefix: '', layout: 'azerty', mode: 'strong' });

describe('HappyPath', () => {
  it('enregistre position, touche décodée et temps', () => {
    const log = new TapLog();
    log.add(tap(5));
    assert.equal(log.length, 1);
    assert.deepEqual(log.taps[0], tap(5));
  });

  it('s’exporte en JSON sérialisable avec les réglages', () => {
    const log = new TapLog();
    log.add(tap(1));
    const json = JSON.parse(JSON.stringify(log.toJSON({ 'adapt.mode': 'calm' })));
    assert.equal(json.format, 'nextglyph-prototype-taps');
    assert.equal(json.settings['adapt.mode'], 'calm');
    assert.equal(json.taps.length, 1);
  });

  it('copie les frappes (pas de référence partagée)', () => {
    const log = new TapLog();
    const t = tap(1);
    log.add(t);
    t.key = 'q';
    assert.equal(log.taps[0].key, 'z');
  });
});

describe('ErrorCases', () => {
  it('refuse une taille maximale invalide', () => {
    assert.throws(() => new TapLog({ max: 0 }), RangeError);
    assert.throws(() => new TapLog({ max: 1.5 }), /taille/);
  });

  it('effacer vide le journal', () => {
    const log = new TapLog();
    log.add(tap(1));
    log.clear();
    assert.equal(log.length, 0);
  });
});

describe('BoundaryValues', () => {
  it('journal vide exportable', () => {
    assert.deepEqual(new TapLog().toJSON().taps, []);
  });

  it('maximum exact conservé, maximum + 1 supprime la plus ancienne', () => {
    const log = new TapLog({ max: 3 });
    for (let i = 1; i <= 3; i++) log.add(tap(i));
    assert.equal(log.length, 3);
    log.add(tap(4));
    assert.equal(log.length, 3);
    assert.equal(log.taps[0].t, 2);
  });
});
