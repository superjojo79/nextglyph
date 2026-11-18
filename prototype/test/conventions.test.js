// Convention de tests du projet (docs/TESTING.md), transposée à Node :
// chaque fichier *.test.js contient trois blocs describe('HappyPath'),
// describe('ErrorCases') et describe('BoundaryValues'), chacun avec au moins un test.

import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';

const REQUIRED = ['HappyPath', 'ErrorCases', 'BoundaryValues'];
const TEST_DIR = new URL('./', import.meta.url);

/** Remplace commentaires et contenu des chaînes par des blancs (positions conservées). */
export function blank(source) {
  let out = '';
  let i = 0;
  const skip = (to) => {
    out += ' '.repeat(Math.max(0, to - i));
    i = to;
  };
  while (i < source.length) {
    const c = source[i];
    const n = source[i + 1];
    if (c === '/' && n === '/') {
      let j = i;
      while (j < source.length && source[j] !== '\n') j++;
      skip(j);
    } else if (c === '/' && n === '*') {
      const end = source.indexOf('*/', i + 2);
      skip(end < 0 ? source.length : end + 2);
    } else if (c === "'" || c === '"' || c === '`') {
      out += c;
      i++;
      while (i < source.length && source[i] !== c) {
        if (source[i] === '\\') {
          out += ' ';
          i++;
        }
        out += ' ';
        i++;
      }
      out += c;
      i++;
    } else {
      out += c;
      i++;
    }
  }
  return out;
}

/** Corps (texte entre accolades) du callback du describe `name`, ou null. */
export function describeBody(source, name) {
  const clean = blank(source);
  const re = new RegExp(`describe\\(\\s*'${name}'`, 'g');
  for (const m of source.matchAll(re)) {
    if (!clean.startsWith('describe(', m.index)) continue; // dans un commentaire ou une chaîne
    const open = clean.indexOf('{', m.index);
    if (open < 0) return null;
    let depth = 0;
    for (let i = open; i < clean.length; i++) {
      if (clean[i] === '{') depth++;
      else if (clean[i] === '}' && --depth === 0) return clean.slice(open + 1, i);
    }
  }
  return null;
}

/** Nombre de tests (it/test) directement présents dans un corps. */
export function countTests(body) {
  return (body.match(/(?<![\w.])(?:it|test)\s*\(/g) ?? []).length;
}

export function violations(source) {
  const problems = [];
  for (const name of REQUIRED) {
    const body = describeBody(source, name);
    if (body === null) problems.push(`bloc describe('${name}') absent`);
    else if (countTests(body) === 0) problems.push(`bloc describe('${name}') vide`);
  }
  return problems;
}

const files = readdirSync(TEST_DIR).filter((f) => f.endsWith('.test.js'));

describe('HappyPath', () => {
  it('chaque fichier de test respecte la convention des trois blocs non vides', () => {
    assert.ok(files.length > 0);
    for (const f of files) {
      const problems = violations(readFileSync(new URL(f, TEST_DIR), 'utf8'));
      assert.deepEqual(problems, [], `${f} : ${problems.join(', ')}`);
    }
  });

  it('détecte un fichier conforme', () => {
    const ok = `describe('HappyPath', () => { it('a', () => {}); });
      describe('ErrorCases', () => { test('b', () => {}); });
      describe('BoundaryValues', () => { it('c', () => {}); });`;
    assert.deepEqual(violations(ok), []);
  });
});

describe('ErrorCases', () => {
  it('détecte un bloc absent', () => {
    const src = `describe('HappyPath', () => { it('a', () => {}); });`;
    assert.deepEqual(violations(src), [
      "bloc describe('ErrorCases') absent",
      "bloc describe('BoundaryValues') absent",
    ]);
  });

  it('détecte un bloc vide', () => {
    const src = `describe('HappyPath', () => { });
      describe('ErrorCases', () => { it('b', () => {}); });
      describe('BoundaryValues', () => { /* it('commenté', () => {}) */ });`;
    assert.deepEqual(violations(src), [
      "bloc describe('HappyPath') vide",
      "bloc describe('BoundaryValues') vide",
    ]);
  });

  it('un it(...) dans une chaîne ne compte pas comme test', () => {
    const src = `describe('HappyPath', () => { const s = "it('x')"; });
      describe('ErrorCases', () => { it('b', () => {}); });
      describe('BoundaryValues', () => { it('c', () => {}); });`;
    assert.deepEqual(violations(src), ["bloc describe('HappyPath') vide"]);
  });
});

describe('BoundaryValues', () => {
  it('un seul test par bloc suffit', () => {
    assert.equal(countTests("it('a', () => {})"), 1);
  });

  it('source vide : trois blocs absents', () => {
    assert.equal(violations('').length, 3);
  });

  it('accolades imbriquées et dans des chaînes', () => {
    const src = `describe('HappyPath', () => { it('a', () => { if (true) { const t = '}'; } }); });
      describe('ErrorCases', () => { it('b', () => {}); });
      describe('BoundaryValues', () => { it('c', () => {}); });`;
    assert.deepEqual(violations(src), []);
  });

  it('n’accepte que des fichiers .test.js dans test/', () => {
    const others = readdirSync(TEST_DIR).filter((f) => !f.endsWith('.test.js'));
    assert.deepEqual(others, []);
  });
});
