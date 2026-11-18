// Données de test partagées (hors dossier test/ : non exécutées comme tests).

import { readFileSync } from 'node:fs';
import { defaults } from '../src/settings.js';
import { Engine } from '../src/engine.js';

export function loadSample(lang) {
  return JSON.parse(readFileSync(new URL(`../data/${lang}.json`, import.meta.url), 'utf8'));
}

export function sampleEngine(overrides = {}) {
  const settings = { ...defaults(), ...overrides };
  return new Engine({ fr: loadSample('fr'), en: loadSample('en') }, settings);
}

/** Tape une chaîne de lettres puis, si `then` est fourni, des actions. */
export function typeText(engine, text) {
  for (const ch of text) {
    if (ch === ' ') engine.editor.space();
    else engine.editor.typeLetter(ch);
  }
}

export const MINI_FR = [
  { w: 'bonjour', f: 1000 },
  { w: 'bonsoir', f: 300 },
  { w: 'école', f: 500 },
  { w: 'a', f: 5000 },
  { w: 'à', f: 3000 },
  { w: "j'ai", f: 2000 },
  { w: 'peut-être', f: 800 },
  { w: 'table', f: 400 },
  { w: 'été', f: 600 },
  { w: 'pêche', f: 50 },
  { w: 'péché', f: 40 },
];

export const MINI_EN = [
  { w: 'hello', f: 1000 },
  { w: 'table', f: 400 },
  { w: "don't", f: 900 },
  { w: 'well', f: 700 },
  { w: "we'll", f: 300 },
  { w: 'cafe', f: 100 },
  { w: 'I', f: 4000 },
  { w: "I'm", f: 3000 },
];
