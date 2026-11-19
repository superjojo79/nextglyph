import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { SOURCE_GENERATED, SOURCE_SAMPLE, describeSources, loadLexiconData } from '../src/datasource.js';

/** fetch factice : `files` associe une URL à son contenu ; une valeur Error simule une panne réseau. */
function fakeFetch(files) {
  return async (url) => {
    if (!(url in files)) return { ok: false, status: 404, json: async () => ({}) };
    const content = files[url];
    if (content instanceof Error) throw content;
    return { ok: true, status: 200, json: async () => (typeof content === 'function' ? content() : content) };
  };
}

describe('HappyPath', () => {
  it('préfère le fichier généré', async () => {
    const fetchFn = fakeFetch({ 'data/fr.json': [{ w: 'a', f: 1 }], 'data/sample-fr.json': [{ w: 'b', f: 1 }] });
    const res = await loadLexiconData(fetchFn, 'fr');
    assert.equal(res.source, SOURCE_GENERATED);
    assert.deepEqual(res.entries, [{ w: 'a', f: 1 }]);
  });

  it('se rabat sur l’exemple si le fichier généré est absent', async () => {
    const res = await loadLexiconData(fakeFetch({ 'data/sample-en.json': [{ w: 'b', f: 2 }] }), 'en');
    assert.equal(res.source, SOURCE_SAMPLE);
    assert.deepEqual(res.entries, [{ w: 'b', f: 2 }]);
  });

  it('décrit les sources chargées, avec un renvoi au README pour les exemples', () => {
    const text = describeSources({
      fr: { entries: [1, 2, 3], source: SOURCE_GENERATED },
      en: { entries: [1], source: SOURCE_SAMPLE },
    });
    assert.equal(
      text,
      'Lexiques — FR : 3 mots (générés) ; EN : 1 mots (exemple) ; lexiques complets : voir README (exportPrototypeData)',
    );
  });

  it('n’ajoute aucun renvoi quand tout est généré', () => {
    const text = describeSources({ fr: { entries: [1], source: SOURCE_GENERATED } });
    assert.equal(text, 'Lexiques — FR : 1 mots (générés)');
  });
});

describe('ErrorCases', () => {
  it('se rabat sur l’exemple si le fichier généré n’est pas du JSON valide', async () => {
    const bad = () => {
      throw new SyntaxError('Unexpected token');
    };
    const fetchFn = fakeFetch({ 'data/fr.json': bad, 'data/sample-fr.json': [{ w: 'b', f: 1 }] });
    assert.equal((await loadLexiconData(fetchFn, 'fr')).source, SOURCE_SAMPLE);
  });

  it('se rabat sur l’exemple si le fichier généré n’est pas un tableau', async () => {
    const fetchFn = fakeFetch({ 'data/fr.json': { w: 'a' }, 'data/sample-fr.json': [] });
    assert.equal((await loadLexiconData(fetchFn, 'fr')).source, SOURCE_SAMPLE);
  });

  it('se rabat sur l’exemple si la requête échoue', async () => {
    const fetchFn = fakeFetch({ 'data/fr.json': new Error('réseau'), 'data/sample-fr.json': [{ w: 'b', f: 1 }] });
    assert.equal((await loadLexiconData(fetchFn, 'fr')).source, SOURCE_SAMPLE);
  });

  it('échoue en nommant l’exemple quand aucun fichier n’est utilisable', async () => {
    await assert.rejects(loadLexiconData(fakeFetch({}), 'en'), /data\/sample-en\.json/);
  });

  it('échoue si l’exemple lui-même n’est pas un tableau', async () => {
    await assert.rejects(loadLexiconData(fakeFetch({ 'data/sample-en.json': 'x' }), 'en'), /invalide/);
  });
});

describe('BoundaryValues', () => {
  it('accepte un fichier généré vide (tableau vide)', async () => {
    const res = await loadLexiconData(fakeFetch({ 'data/fr.json': [] }), 'fr');
    assert.equal(res.source, SOURCE_GENERATED);
    assert.deepEqual(res.entries, []);
  });

  it('décrit un ensemble vide de langues', () => {
    assert.equal(describeSources({}), 'Lexiques — ');
  });
});
