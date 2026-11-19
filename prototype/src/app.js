// Interface du prototype : affichage, Pointer Events, panneau de réglages.
// Toute la logique est dans les modules purs voisins.

import { describeSources, loadLexiconData } from './datasource.js';
import { Engine } from './engine.js';
import { LayoutFreezer } from './freeze.js';
import { decodeTap, hitTest } from './decoder.js';
import { LAYOUTS, ROW_UNITS } from './layout.js';
import { TapLog } from './log.js';
import { SCHEMA, defaults, sanitize, sanitizeValue } from './settings.js';

const STORAGE_KEY = 'nextglyph-prototype-settings';
const GAP_PX = 4;

const $ = (id) => document.getElementById(id);
const els = {
  out: $('out'),
  kb: $('kb'),
  bar: $('bar'),
  debug: $('debug'),
  error: $('error'),
  dataSource: $('data-source'),
  settings: $('settings'),
  form: $('settings-form'),
  logCount: $('log-count'),
};

const OPTION_LABELS = {
  azerty: 'AZERTY',
  qwerty: 'QWERTY',
  off: 'Désactivé',
  calm: 'Calme',
  strong: 'Calme renforcé',
  elastic: 'Élastique',
  both: 'Les deux',
  fr: 'Français',
  en: 'Anglais',
  auto: 'Automatique',
};

const KEY_LABELS = { sym: '?123', enter: '↵', back: '⌫' };

function loadSettings() {
  try {
    return sanitize(JSON.parse(localStorage.getItem(STORAGE_KEY) ?? 'null'));
  } catch {
    return defaults();
  }
}

function saveSettings() {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(settings));
  } catch {
    // stockage indisponible : les réglages ne sont simplement pas conservés
  }
}

const settings = loadSettings();
const log = new TapLog();
let engine = null;
let freezer = null;
let keyEls = new Map();
let shownLayout = null;
let lastDist = null;
let timer = null;
let unitPx = 36;

function reducedMotion() {
  return window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
}

function showError(message) {
  els.error.hidden = false;
  els.error.textContent = message;
}

// ---------- Construction du clavier ----------

function buildKeyboard() {
  els.kb.textContent = '';
  keyEls = new Map();
  for (const row of LAYOUTS[settings.layout]) {
    for (const k of row) {
      if (k.kind === 'spacer') continue;
      const key = document.createElement('div');
      key.className = `key ${k.kind === 'letter' ? 'letter' : 'fn'}`;
      key.dataset.id = k.id;
      const face = document.createElement('div');
      const lbl = document.createElement('span');
      lbl.className = 'lbl';
      face.append(lbl);
      if (k.kind === 'letter') {
        const dbg = document.createElement('span');
        dbg.className = 'dbg';
        face.append(dbg);
      }
      key.append(face);
      els.kb.append(key);
      keyEls.set(k.id, key);
    }
  }
}

function measure() {
  const rect = els.kb.getBoundingClientRect();
  unitPx = rect.width / ROW_UNITS;
  engine.rowPitch = (settings['ui.rowHeight'] + GAP_PX) / unitPx;
}

function render(layout, view) {
  shownLayout = layout;
  const pitchPx = settings['ui.rowHeight'] + GAP_PX;
  for (const k of layout.keys) {
    const el = keyEls.get(k.id);
    if (!el) continue;
    el.style.left = `${(k.x / ROW_UNITS) * 100}%`;
    el.style.width = `${(k.w / ROW_UNITS) * 100}%`;
    el.style.top = `${k.row * pitchPx}px`;
    el.style.opacity = String(k.opacity);
    const face = el.firstChild;
    const lbl = face.firstChild;
    lbl.style.setProperty('--ls', String(k.labelScale));
    if (k.kind === 'letter') {
      lbl.textContent = view.capNext ? k.id.toUpperCase() : k.id;
      face.lastChild.textContent = settings['ui.debug'] ? k.r.toFixed(2).replace(/^0\./, '.') : '';
    } else if (k.kind === 'space') {
      const label = view.space.preview ? `␣ ${view.space.preview}` : '␣';
      lbl.replaceChildren(label);
      const small = document.createElement('small');
      small.textContent = view.space.lang.toUpperCase();
      lbl.append(small);
    } else {
      lbl.textContent = KEY_LABELS[k.id] ?? k.id;
    }
  }
}

function renderText(view) {
  els.out.replaceChildren(view.text);
  if (view.composing) {
    const span = document.createElement('span');
    span.className = 'comp';
    span.textContent = view.composing;
    els.out.append(span);
  }
  const caret = document.createElement('span');
  caret.className = 'caret';
  els.out.append(caret);
  els.out.scrollTop = els.out.scrollHeight;
}

function renderBar(view) {
  els.bar.className = `bar ${view.bar.mode === 'punct' ? 'punct' : ''}`;
  els.bar.replaceChildren();
  const items = view.bar.mode === 'empty' ? [null, null, null] : view.bar.items;
  for (const item of items) {
    const b = document.createElement('button');
    b.type = 'button';
    if (item) {
      b.textContent = item.text;
      b.dataset.text = item.text;
      if (item.dominant) b.className = 'dominant';
    }
    els.bar.append(b);
  }
}

function renderDebug(view) {
  els.debug.hidden = !settings['ui.debug'];
  if (!settings['ui.debug'] || !lastDist) return;
  const pi = Object.entries(view.pi)
    .map(([l, p]) => `${l.toUpperCase()} ${(p * 100).toFixed(0)} %`)
    .join(' · ');
  els.debug.textContent =
    `préfixe « ${engine.editor.composing} » · P(fin) = ${lastDist.pEnd.toFixed(2)}` +
    `${lastDist.known ? '' : ' · préfixe inconnu : neutre'}${lastDist.corrected ? ' · préfixe corrigé' : ''}\n` +
    `langue estimée : ${view.space.lang.toUpperCase()} (${pi}) · phrase : ${engine.tracker.sentenceLang().toUpperCase()}`;
}

function refresh({ forceLayout = false } = {}) {
  const view = engine.editor.view();
  const { layout, dist } = engine.targetLayout();
  lastDist = dist;
  freezer.setTarget(layout);
  renderText(view);
  renderBar(view);
  els.logCount.textContent = `${log.length} frappes journalisées`;
  // Le libellé de l'espace et les éléments hors géométrie suivent immédiatement ;
  // la géométrie, elle, attend le relâchement (gel).
  if (shownLayout) {
    for (const k of shownLayout.keys) {
      if (k.kind === 'space') {
        const lbl = keyEls.get('space').firstChild.firstChild;
        const label = view.space.preview ? `␣ ${view.space.preview}` : '␣';
        lbl.replaceChildren(label);
        const small = document.createElement('small');
        small.textContent = view.space.lang.toUpperCase();
        lbl.append(small);
      }
    }
  }
  scheduleApply(forceLayout);
  renderDebug(view);
}

function scheduleApply(force) {
  clearTimeout(timer);
  const now = performance.now();
  if (force && freezer.down.size === 0) freezer.deferred = false;
  const delay = freezer.nextDelay(now);
  if (delay === null) return; // doigt posé : on attend le relâchement
  const run = () => {
    if (freezer.apply(performance.now())) render(freezer.displayed, engine.editor.view());
  };
  if (delay <= 0) run();
  else timer = setTimeout(run, delay);
}

// ---------- Pointeurs ----------

function toUnits(ev) {
  const rect = els.kb.getBoundingClientRect();
  return { x: (ev.clientX - rect.left) / unitPx, y: (ev.clientY - rect.top) / unitPx };
}

function pressKey(id, on) {
  keyEls.get(id)?.classList.toggle('pressed', on);
}

const downKey = new Map();

els.kb.addEventListener('pointerdown', (ev) => {
  if (!engine) return;
  ev.preventDefault();
  try {
    els.kb.setPointerCapture(ev.pointerId);
  } catch {
    // pointeur déjà terminé : sans conséquence
  }
  clearTimeout(timer);
  freezer.pointerDown(ev.pointerId, performance.now());
  const hit = hitTest(toUnits(ev), freezer.displayed);
  downKey.set(ev.pointerId, hit?.id ?? null);
  if (hit) pressKey(hit.id, true);
});

function release(ev, cancelled) {
  if (!downKey.has(ev.pointerId)) return;
  const downId = downKey.get(ev.pointerId);
  downKey.delete(ev.pointerId);
  if (downId) pressKey(downId, false);
  const now = performance.now();
  if (!cancelled) handleTap(toUnits(ev), now);
  freezer.pointerUp(ev.pointerId, now);
  refresh();
}

els.kb.addEventListener('pointerup', (ev) => release(ev, false));
els.kb.addEventListener('pointercancel', (ev) => release(ev, true));

function handleTap(point, now) {
  const layout = freezer.displayed; // disposition gelée pendant le toucher
  const hit = hitTest(point, layout);
  if (!hit) return;
  const editor = engine.editor;
  const prefix = editor.composing;
  let produced = hit.id;
  if (hit.kind === 'letter') {
    const dist = editor.distribution();
    const letterKeys = layout.keys.filter((k) => k.kind === 'letter');
    const useModel = settings['decode.lambda'] > 0;
    const { letter } = decodeTap(point, letterKeys, useModel ? dist.p : null, {
      alpha: settings['decode.alpha'],
      lambda: settings['decode.lambda'],
      sigma: settings['decode.sigma'],
    });
    produced = letter;
    editor.typeLetter(letter);
    flash(letter);
  } else if (hit.kind === 'space') {
    editor.space();
  } else if (hit.id === 'back') {
    editor.backspace();
  } else if (hit.id === 'enter') {
    editor.enter();
  }
  log.add({
    t: Math.round(now * 10) / 10,
    x: Math.round(point.x * 1000) / 1000,
    y: Math.round(point.y * 1000) / 1000,
    hit: hit.id,
    key: produced,
    prefix,
    layout: settings.layout,
    mode: settings['adapt.mode'],
  });
}

function flash(id) {
  const el = keyEls.get(id);
  if (!el) return;
  el.classList.add('flash');
  setTimeout(() => el.classList.remove('flash'), 140);
}

els.bar.addEventListener('click', (ev) => {
  const b = ev.target.closest('button');
  if (!b || !b.dataset.text || !engine) return;
  const mode = engine.editor.view().bar.mode;
  if (mode === 'words') engine.editor.tapSuggestion(b.dataset.text);
  else if (mode === 'punct') engine.editor.tapSign(b.dataset.text);
  refresh({ forceLayout: true });
});

// ---------- Réglages ----------

function buildSettingsForm() {
  els.form.replaceChildren();
  const groups = new Map();
  for (const spec of SCHEMA) {
    if (!groups.has(spec.group)) {
      const fs = document.createElement('fieldset');
      const lg = document.createElement('legend');
      lg.textContent = spec.group;
      fs.append(lg);
      els.form.append(fs);
      groups.set(spec.group, fs);
    }
    const label = document.createElement('label');
    const name = document.createElement('span');
    name.textContent = `${spec.label}`;
    name.title = spec.key;
    label.append(name);
    let input;
    if (spec.type === 'boolean') {
      label.className = 'bool';
      input = document.createElement('input');
      input.type = 'checkbox';
      input.checked = settings[spec.key];
    } else if (spec.type === 'enum') {
      input = document.createElement('select');
      for (const o of spec.options) {
        const opt = document.createElement('option');
        opt.value = o;
        opt.textContent = OPTION_LABELS[o] ?? o;
        input.append(opt);
      }
      input.value = settings[spec.key];
    } else {
      input = document.createElement('input');
      input.type = 'range';
      input.min = spec.min;
      input.max = spec.max;
      input.step = spec.step;
      input.value = settings[spec.key];
    }
    label.append(input);
    let out = null;
    if (spec.type === 'number') {
      out = document.createElement('output');
      out.textContent = String(settings[spec.key]);
      label.append(out);
    }
    input.addEventListener('input', () => {
      const raw = spec.type === 'boolean' ? input.checked : spec.type === 'number' ? Number(input.value) : input.value;
      settings[spec.key] = sanitizeValue(spec.key, raw);
      if (out) out.textContent = String(settings[spec.key]);
      saveSettings();
      onSettingChanged(spec.key);
    });
    groups.get(spec.group).append(label);
  }
}

function applyCssSettings() {
  const root = document.documentElement.style;
  root.setProperty('--row-h', `${settings['ui.rowHeight']}px`);
  root.setProperty('--anim', reducedMotion() ? '0ms' : `${settings['adapt.animationMs']}ms`);
}

function onSettingChanged(key) {
  engine.applySettings();
  applyCssSettings();
  if (key === 'layout') {
    buildKeyboard();
    shownLayout = null;
  }
  measure();
  const { layout } = engine.targetLayout();
  freezer.displayed = layout;
  freezer.target = null;
  freezer.fastTypingMs = settings['adapt.fastTypingMs'];
  render(layout, engine.editor.view());
  refresh({ forceLayout: true });
}

$('btn-settings').addEventListener('click', () => {
  const open = els.settings.hidden;
  els.settings.hidden = !open;
  $('btn-settings').setAttribute('aria-expanded', String(open));
});

$('btn-clear').addEventListener('click', () => {
  engine.editor.reset();
  engine.tracker.words = [];
  engine.tracker.sentence = 0;
  refresh({ forceLayout: true });
});

$('btn-reset').addEventListener('click', () => {
  Object.assign(settings, defaults());
  saveSettings();
  buildSettingsForm();
  onSettingChanged('layout');
});

$('btn-clearlog').addEventListener('click', () => {
  log.clear();
  els.logCount.textContent = '0 frappes journalisées';
});

$('btn-export').addEventListener('click', () => {
  const blob = new Blob([JSON.stringify(log.toJSON({ ...settings, rowPitchU: engine.rowPitch }), null, 1)], {
    type: 'application/json',
  });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `nextglyph-frappes-${new Date().toISOString().replace(/[:.]/g, '-')}.json`;
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
});

window.addEventListener('resize', () => {
  if (!engine) return;
  measure();
  const { layout } = engine.targetLayout();
  freezer.displayed = layout;
  render(layout, engine.editor.view());
});

// ---------- Démarrage ----------

async function start() {
  try {
    const [fr, en] = await Promise.all(['fr', 'en'].map((lang) => loadLexiconData(fetch, lang)));
    els.dataSource.textContent = describeSources({ fr, en });
    engine = new Engine({ fr: fr.entries, en: en.entries }, settings);
  } catch (err) {
    showError(
      `Impossible de charger les lexiques (${err.message}). Servez le dossier prototype/ en HTTP ` +
        '(ex. « python -m http.server »), l’ouverture directe du fichier ne fonctionne pas.',
    );
    return;
  }
  freezer = new LayoutFreezer({ fastTypingMs: settings['adapt.fastTypingMs'] });
  applyCssSettings();
  buildKeyboard();
  buildSettingsForm();
  measure();
  const { layout } = engine.targetLayout();
  freezer.displayed = layout;
  render(layout, engine.editor.view());
  refresh();
}

start();
