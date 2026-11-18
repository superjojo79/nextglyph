import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { LayoutFreezer } from '../src/freeze.js';

describe('HappyPath', () => {
  it('la disposition ne change jamais tant qu’un pointeur est posé', () => {
    const f = new LayoutFreezer({ fastTypingMs: 120, initial: 'A' });
    f.pointerDown(1, 1000);
    f.setTarget('B');
    assert.equal(f.apply(1500), false);
    assert.equal(f.displayed, 'A');
    assert.equal(f.nextDelay(1500), null);
  });

  it('applique la cible après le relâchement', () => {
    const f = new LayoutFreezer({ initial: 'A' });
    f.pointerDown(1, 1000);
    f.setTarget('B');
    f.pointerUp(1, 1050);
    assert.equal(f.nextDelay(1050), 0);
    assert.equal(f.apply(1050), true);
    assert.equal(f.displayed, 'B');
  });

  it('multi-touch : attend le relâchement du dernier doigt', () => {
    const f = new LayoutFreezer({ initial: 'A' });
    f.pointerDown(1, 0);
    f.pointerDown(2, 500);
    f.setTarget('B');
    f.pointerUp(1, 600);
    assert.equal(f.apply(700), false);
    f.pointerUp(2, 800);
    assert.equal(f.apply(800), true);
  });
});

describe('ErrorCases', () => {
  it('sans cible, rien à appliquer', () => {
    const f = new LayoutFreezer({ initial: 'A' });
    assert.equal(f.apply(0), false);
    assert.equal(f.nextDelay(0), null);
  });

  it('relâcher un pointeur inconnu est sans effet', () => {
    const f = new LayoutFreezer({ initial: 'A' });
    f.pointerUp(99, 100);
    assert.equal(f.lastUp, -Infinity);
    assert.equal(f.down.size, 0);
  });
});

describe('BoundaryValues', () => {
  it('saisie rapide : intervalle inférieur à fastTypingMs, mise à jour différée', () => {
    const f = new LayoutFreezer({ fastTypingMs: 120, initial: 'A' });
    f.pointerDown(1, 1000);
    f.pointerUp(1, 1030);
    f.pointerDown(2, 1100); // 100 ms < 120 ms
    f.pointerUp(2, 1130);
    f.setTarget('B');
    assert.equal(f.apply(1140), false);
    assert.equal(f.nextDelay(1140), 110);
    assert.equal(f.apply(1250), true);
  });

  it('intervalle égal à fastTypingMs : pas de report', () => {
    const f = new LayoutFreezer({ fastTypingMs: 120, initial: 'A' });
    f.pointerDown(1, 1000);
    f.pointerUp(1, 1030);
    f.pointerDown(2, 1120);
    f.pointerUp(2, 1150);
    f.setTarget('B');
    assert.equal(f.apply(1150), true);
  });

  it('fastTypingMs = 0 : jamais de report', () => {
    const f = new LayoutFreezer({ fastTypingMs: 0, initial: 'A' });
    f.pointerDown(1, 10);
    f.pointerUp(1, 11);
    f.pointerDown(2, 12);
    f.pointerUp(2, 13);
    f.setTarget('B');
    assert.equal(f.apply(13), true);
  });
});
