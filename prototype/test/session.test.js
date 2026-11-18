import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { Engine } from '../src/engine.js';
import { defaults } from '../src/settings.js';
import { MINI_EN, MINI_FR, sampleEngine, typeText } from '../testkit/fixtures.js';
import { NNBSP } from '../src/punctuation.js';

const mini = (overrides = {}) => new Engine({ fr: MINI_FR, en: MINI_EN }, { ...defaults(), ...overrides });

describe('HappyPath', () => {
  it('espace : valide un mot par correction, annulable par ⌫ immédiat', () => {
    const e = mini();
    typeText(e, 'bonjoru ');
    assert.equal(e.editor.committed, 'Bonjour ');
    assert.equal(e.editor.backspace(), 'undo');
    assert.equal(e.editor.committed, '');
    assert.equal(e.editor.composing, 'bonjoru');
    e.editor.space(); // l'annulation ne se rejoue pas automatiquement
    assert.equal(e.editor.committed, 'Bonjoru ');
  });

  it('un deuxième ⌫ efface normalement', () => {
    const e = mini();
    typeText(e, 'bonjoru ');
    e.editor.backspace();
    e.editor.backspace();
    assert.equal(e.editor.composing, 'bonjor');
  });

  it('ponctuation : remplace l’espace automatique, typographie française', () => {
    const e = mini();
    typeText(e, 'bonjour ');
    assert.equal(e.editor.view().bar.mode, 'punct');
    e.editor.tapSign('!');
    assert.equal(e.editor.committed, `Bonjour${NNBSP}! `);
    typeText(e, 'table ');
    e.editor.tapSign(',');
    assert.equal(e.editor.committed, `Bonjour${NNBSP}! Table, `);
  });

  it('double espace → point, puis majuscule', () => {
    const e = mini();
    typeText(e, 'table  ');
    assert.equal(e.editor.committed, 'Table. ');
    assert.equal(e.editor.capNext(), true);
    typeText(e, 'hello');
    assert.equal(e.editor.view().composing, 'Hello');
  });

  it('majuscule automatique au début du champ seulement', () => {
    const e = mini();
    assert.equal(e.editor.capNext(), true);
    typeText(e, 'table table ');
    assert.equal(e.editor.committed, 'Table table ');
  });

  it('suggestions : dominant au centre, surligné ; libellé « ␣ mot » sur l’espace', () => {
    const e = mini();
    typeText(e, 'bonj');
    const v = e.editor.view();
    assert.equal(v.bar.items[1].text, 'Bonjour');
    assert.equal(v.bar.items[1].dominant, true);
    assert.equal(v.space.preview, 'Bonjour');
  });

  it('tap sur une suggestion : valide et ajoute une espace automatique', () => {
    const e = mini();
    typeText(e, 'bons');
    e.editor.tapSuggestion('bonsoir');
    assert.equal(e.editor.committed, 'Bonsoir ');
    assert.equal(e.editor.view().bar.mode, 'punct');
  });

  it('bilinguisme : la langue estimée suit les mots validés', () => {
    const e = sampleEngine();
    typeText(e, 'hello world the people are good ');
    assert.equal(e.editor.view().space.lang, 'en');
    typeText(e, 'bonjour le monde est très grand ');
    assert.equal(e.editor.view().space.lang, 'fr');
  });

  it('préfixe inconnu : clavier neutre', () => {
    const e = sampleEngine();
    typeText(e, 'qqqqq');
    const { layout, dist } = e.targetLayout();
    assert.equal(dist.known, false);
    assert.ok(layout.keys.every((k) => k.w === k.rest));
  });
});

describe('ErrorCases', () => {
  it('refuse une liste de lexiques vide', () => {
    assert.throws(() => new Engine({}, defaults()), RangeError);
  });

  it('⌫ sur un champ vide : aucun effet', () => {
    const e = mini();
    assert.equal(e.editor.backspace(), 'none');
    assert.equal(e.editor.committed, '');
  });

  it('après autoSpace puis une lettre, ⌫ n’annule plus rien', () => {
    const e = mini();
    typeText(e, 'bonjoru b');
    e.editor.backspace();
    assert.equal(e.editor.backspace(), 'delete');
    assert.equal(e.editor.committed, '');
    assert.equal(e.editor.composing, 'bonjour'); // le mot est rouvert, sans annulation de correction
  });

  it('une même correction annulée deux fois n’est plus proposée automatiquement', () => {
    const e = mini({ 'autocommit.rejectLimit': 2 });
    for (let i = 0; i < 2; i++) {
      typeText(e, 'bonjoru ');
      assert.equal(e.editor.backspace(), 'undo');
      e.editor.composing = '';
      e.editor.committed = '';
    }
    typeText(e, 'bonjoru ');
    assert.equal(e.editor.committed, 'Bonjoru ');
  });

  it('validation par espace désactivée : mot inséré tel quel', () => {
    const e = mini({ 'autocommit.enabled': false });
    typeText(e, 'bonjoru ');
    assert.equal(e.editor.committed, 'Bonjoru ');
  });
});

describe('BoundaryValues', () => {
  it('espace sur mot vide au début : espace simple, pas de double espace', () => {
    const e = mini();
    e.editor.space();
    assert.equal(e.editor.committed, ' ');
  });

  it('espace après un signe : rien de plus (pas d’espace double)', () => {
    const e = mini();
    typeText(e, 'table ');
    e.editor.tapSign(',');
    e.editor.space();
    assert.equal(e.editor.committed, 'Table, ');
  });

  it('double espace désactivé : une espace de plus', () => {
    const e = mini({ 'typo.doubleSpace': false });
    typeText(e, 'table  ');
    assert.equal(e.editor.committed, 'Table  ');
  });

  it('typographie française désactivée : aucune espace fine', () => {
    const e = mini({ 'typo.french': false });
    typeText(e, 'table ');
    e.editor.tapSign('?');
    assert.equal(e.editor.committed, 'Table? ');
  });

  it('⌫ sur une espace après un mot rouvre le mot en cours', () => {
    const e = mini();
    typeText(e, 'table ');
    e.editor.backspace();
    assert.equal(e.editor.composing, 'table');
    assert.equal(e.editor.capAtStart, true);
  });

  it('majuscules automatiques désactivées', () => {
    const e = mini({ 'caps.auto': false });
    typeText(e, 'table');
    assert.equal(e.editor.view().composing, 'table');
  });
});
