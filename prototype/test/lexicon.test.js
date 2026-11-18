import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { Lexicon, isPlainForm } from '../src/lexicon.js';
import { MINI_FR, loadSample } from '../testkit/fixtures.js';

function pseudoWords(n) {
  // générateur déterministe de mots pseudo-aléatoires
  let seed = 12345;
  const rnd = () => (seed = (seed * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff;
  const out = [];
  for (let i = 0; i < n; i++) {
    const len = 3 + Math.floor(rnd() * 8);
    let w = '';
    for (let j = 0; j < len; j++) w += String.fromCharCode(97 + Math.floor(rnd() * 26));
    out.push({ w: w + i.toString(36), f: 1 + Math.floor(rnd() * 100000) });
  }
  return out;
}

describe('HappyPath', () => {
  const lex = new Lexicon(MINI_FR, { lang: 'fr' });

  it('retrouve les formes d’affichage d’une clé', () => {
    assert.deepEqual(lex.forms('ecole').map((f) => f.w), ['école']);
    assert.deepEqual(lex.forms('a').map((f) => f.w), ['a', 'à']);
  });

  it('les nœuds portent la somme des fréquences du sous-arbre', () => {
    const node = lex.find('bon');
    assert.equal(node.sum, 1300);
    assert.equal(lex.childSum(node, 'j'), 1000);
    assert.equal(lex.total, MINI_FR.reduce((a, e) => a + e.f, 0));
  });

  it('classe les formes simples et composées', () => {
    assert.equal(isPlainForm('table'), true);
    assert.equal(isPlainForm("j'ai"), false);
    assert.equal(lex.hasPlainWord('jai'), false);
    assert.equal(lex.hasPlainWord('table'), true);
  });

  it('top() renvoie les meilleures formes par fréquence décroissante', () => {
    const best = lex.top(lex.find('bon'), 5, { includeOwn: false }).map((f) => f.w);
    assert.deepEqual(best, ['bonjour', 'bonsoir']);
  });

  it('se construit avec les lexiques d’exemple', () => {
    const fr = new Lexicon(loadSample('fr'), { lang: 'fr' });
    assert.ok(fr.size > 900);
    assert.equal(fr.skipped, 0);
  });
});

describe('ErrorCases', () => {
  it('refuse une entrée qui n’est pas un tableau', () => {
    assert.throws(() => new Lexicon({}), /tableau/);
  });

  it('ignore et compte les entrées invalides', () => {
    const lex = new Lexicon([
      { w: 'ok', f: 5 },
      { w: '', f: 5 },
      { w: 'neg', f: -1 },
      { w: 'nan', f: Number.NaN },
      { w: 'deux mots', f: 5 },
      { w: '123', f: 5 },
      null,
      { f: 3 },
    ]);
    assert.equal(lex.size, 1);
    assert.equal(lex.skipped, 7);
  });

  it('un préfixe absent donne null et aucune forme', () => {
    const lex = new Lexicon(MINI_FR);
    assert.equal(lex.find('zzz'), null);
    assert.deepEqual(lex.forms('zzz'), []);
    assert.deepEqual(lex.top(null, 3), []);
  });
});

describe('BoundaryValues', () => {
  it('lexique vide', () => {
    const lex = new Lexicon([]);
    assert.equal(lex.total, 0);
    assert.equal(lex.find('a'), null);
    assert.equal(lex.find(''), lex.root);
  });

  it('un mot en double additionne les fréquences', () => {
    const lex = new Lexicon([{ w: 'a', f: 1 }, { w: 'a', f: 2 }]);
    assert.equal(lex.forms('a')[0].f, 3);
    assert.equal(lex.size, 1);
  });

  it('top(n = 0) est vide, top(n > taille) renvoie tout', () => {
    const lex = new Lexicon(MINI_FR);
    assert.equal(lex.top(lex.root, 0).length, 0);
    assert.equal(lex.top(lex.root, 1000).length, MINI_FR.length);
  });

  it('100 000 mots : construction et requêtes sans ralentissement visible', () => {
    const words = pseudoWords(100_000);
    const t0 = performance.now();
    const lex = new Lexicon(words);
    const build = performance.now() - t0;
    assert.equal(lex.size, 100_000);
    const t1 = performance.now();
    for (let i = 0; i < 1000; i++) {
      const node = lex.find('ab');
      lex.top(node ?? lex.root, 5);
      lex.childSum(lex.root, 'a');
    }
    const query = performance.now() - t1;
    assert.ok(build < 4000, `construction trop lente : ${build} ms`);
    assert.ok(query < 1000, `requêtes trop lentes : ${query} ms`);
  });
});
