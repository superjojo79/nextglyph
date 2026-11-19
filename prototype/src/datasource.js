// Choix du fichier de lexique : le fichier généré (`data/<langue>.json`, produit par
// `./gradlew :tools:exportPrototypeData`, non versionné) s'il existe et est valide,
// sinon le petit lexique d'exemple versionné (`data/sample-<langue>.json`).

export const SOURCE_GENERATED = 'generated';
export const SOURCE_SAMPLE = 'sample';

/** Tente de lire un tableau JSON ; renvoie null si le fichier est absent, illisible ou n'est pas un tableau. */
async function tryRead(fetchFn, url) {
  try {
    const res = await fetchFn(url);
    if (!res.ok) return null;
    const data = await res.json();
    return Array.isArray(data) ? data : null;
  } catch {
    return null;
  }
}

/**
 * Charge les entrées d'une langue.
 * @returns {Promise<{ entries: object[], source: 'generated' | 'sample' }>}
 * @throws {Error} si ni le fichier généré ni l'exemple ne sont utilisables.
 */
export async function loadLexiconData(fetchFn, lang) {
  const generated = await tryRead(fetchFn, `data/${lang}.json`);
  if (generated) return { entries: generated, source: SOURCE_GENERATED };
  const sample = await tryRead(fetchFn, `data/sample-${lang}.json`);
  if (sample) return { entries: sample, source: SOURCE_SAMPLE };
  throw new Error(`data/sample-${lang}.json : introuvable ou invalide`);
}

/** Libellé affiché dans l'interface pour l'ensemble des langues chargées. */
export function describeSources(loaded) {
  const parts = Object.entries(loaded).map(([lang, { entries, source }]) => {
    const kind = source === SOURCE_GENERATED ? 'générés' : 'exemple';
    return `${lang.toUpperCase()} : ${entries.length} mots (${kind})`;
  });
  const anySample = Object.values(loaded).some(({ source }) => source === SOURCE_SAMPLE);
  const hint = anySample ? ' ; lexiques complets : voir README (exportPrototypeData)' : '';
  return `Lexiques — ${parts.join(' ; ')}${hint}`;
}
