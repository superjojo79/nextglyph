// Réglages du prototype : noms alignés sur la spécification (§5.2, §5.3, §5.5, §14).

export const SCHEMA = [
  { key: 'layout', group: 'Clavier', type: 'enum', options: ['azerty', 'qwerty'], def: 'azerty', label: 'Disposition' },
  { key: 'ui.rowHeight', group: 'Clavier', type: 'number', min: 34, max: 56, step: 1, def: 44, label: 'Hauteur des rangées (px)' },
  { key: 'adapt.mode', group: 'Adaptation', type: 'enum', options: ['off', 'calm', 'strong', 'elastic'], def: 'strong', label: 'Mode (Désactivé / Calme / Calme renforcé / Élastique)' },
  { key: 'adapt.minOpacity', group: 'Adaptation', type: 'number', min: 0.1, max: 1, step: 0.05, def: 0.3, label: 'Opacité minimale' },
  { key: 'adapt.minWidth', group: 'Adaptation', type: 'number', min: 0.3, max: 1, step: 0.05, def: 0.6, label: 'Largeur minimale (u)' },
  { key: 'adapt.gain', group: 'Adaptation', type: 'number', min: 0, max: 1.5, step: 0.05, def: 0.5, label: 'Gain de largeur' },
  { key: 'adapt.spaceGain', group: 'Adaptation', type: 'number', min: 0, max: 2, step: 0.05, def: 0.8, label: 'Gain de l’espace (u)' },
  { key: 'adapt.maxDisplacement', group: 'Adaptation', type: 'number', min: 0, max: 1, step: 0.05, def: 0.5, label: 'Déplacement max (largeur de touche)' },
  { key: 'adapt.fastTypingMs', group: 'Adaptation', type: 'number', min: 0, max: 400, step: 10, def: 120, label: 'Saisie rapide (ms)' },
  { key: 'adapt.animationMs', group: 'Adaptation', type: 'number', min: 0, max: 400, step: 10, def: 100, label: 'Animation (ms)' },
  { key: 'decode.alpha', group: 'Décodage', type: 'number', min: 0, max: 1, step: 0.05, def: 0.5, label: 'α (affiché vs repos)' },
  { key: 'decode.lambda', group: 'Décodage', type: 'number', min: 0, max: 2, step: 0.05, def: 0.6, label: 'λ (poids du modèle de langue)' },
  { key: 'decode.sigma', group: 'Décodage', type: 'number', min: 0.15, max: 1.2, step: 0.05, def: 0.3, label: 'σ (u)' },
  { key: 'autocommit.enabled', group: 'Validation', type: 'boolean', def: true, label: 'Validation par espace' },
  { key: 'autocommit.minProb', group: 'Validation', type: 'number', min: 0, max: 1, step: 0.05, def: 0.7, label: 'Probabilité minimale' },
  { key: 'autocommit.minRatio', group: 'Validation', type: 'number', min: 1, max: 10, step: 0.1, def: 2, label: 'Ratio minimal 1er / 2e' },
  { key: 'autocommit.rejectLimit', group: 'Validation', type: 'number', min: 1, max: 5, step: 1, def: 2, label: 'Annulations avant abandon' },
  { key: 'typo.french', group: 'Texte', type: 'boolean', def: true, label: 'Typographie française' },
  { key: 'typo.doubleSpace', group: 'Texte', type: 'boolean', def: true, label: 'Double espace → point' },
  { key: 'caps.auto', group: 'Texte', type: 'boolean', def: true, label: 'Majuscules automatiques' },
  { key: 'lang.active', group: 'Langues', type: 'enum', options: ['both', 'fr', 'en'], def: 'both', label: 'Langues actives' },
  { key: 'lang.preferred', group: 'Langues', type: 'enum', options: ['fr', 'en'], def: 'fr', label: 'Langue préférée de départ' },
  { key: 'lang.lock', group: 'Langues', type: 'enum', options: ['auto', 'fr', 'en'], def: 'auto', label: 'Verrouillage de la langue' },
  { key: 'lang.window', group: 'Langues', type: 'number', min: 1, max: 20, step: 1, def: 4, label: 'Fenêtre du score (mots)' },
  { key: 'ui.debug', group: 'Débogage', type: 'boolean', def: false, label: 'Afficher r(c), P(fin), langue' },
];

const BY_KEY = new Map(SCHEMA.map((s) => [s.key, s]));

export function defaults() {
  return Object.fromEntries(SCHEMA.map((s) => [s.key, s.def]));
}

/** Valide une valeur selon le schéma ; retourne la valeur par défaut si invalide. */
export function sanitizeValue(key, value) {
  const spec = BY_KEY.get(key);
  if (!spec) return undefined;
  switch (spec.type) {
    case 'boolean':
      return typeof value === 'boolean' ? value : spec.def;
    case 'enum':
      return spec.options.includes(value) ? value : spec.def;
    case 'number': {
      if (typeof value !== 'number' || !Number.isFinite(value)) return spec.def;
      return Math.min(spec.max, Math.max(spec.min, value));
    }
    default:
      return spec.def;
  }
}

/** Fusionne des valeurs partielles (ex. lues du stockage) avec les défauts ; clés inconnues ignorées. */
export function sanitize(values) {
  const out = defaults();
  if (values && typeof values === 'object') {
    for (const key of Object.keys(values)) {
      if (BY_KEY.has(key)) out[key] = sanitizeValue(key, values[key]);
    }
  }
  return out;
}
