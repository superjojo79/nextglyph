#!/usr/bin/env bash
# Lance les tests de fuzzing Jazzer du moteur (docs/TESTING.md, « Tests de fuzzing »).
#
# Jazzer ne fait tourner qu'un test de fuzzing par JVM : on lance donc Gradle une fois par cible.
# La durée de chaque cible est le `maxDuration` de son @FuzzTest. Sans argument, toutes les cibles ;
# sinon, celles dont le nom contient l'un des arguments (ex. : `scripts/fuzz.sh Lexicon Ngram`).
# Un plantage trouvé par le fuzzer est écrit dans engine/src/test/resources/…/Inputs/ (ou à la racine
# du module) : le rejouer avec `./gradlew :engine:test` (sans JAZZER_FUZZ) et en faire un test de régression.
set -euo pipefail
cd "$(dirname "$0")/.."

TARGETS=(
  "LexiconFuzzTest arbitraryBytesOnlyRaiseFormatException"
  "NgramModelFuzzTest arbitraryBytesOnlyRaiseFormatException"
  "LearnedDataFileFuzzTest arbitraryBytesNeverThrow"
  "LearnedDataFileFuzzTest wellFormedSectionsNeverThrow"
  "LearnedDataFileFuzzTest arbitraryStreamNeverThrows"
  "ByteCodecFuzzTest readerOnlyRaisesIllegalArgument"
  "ByteCodecFuzzTest readAtMostReturnsBoundedPrefix"
  "StoredStateFuzzTest arbitraryHeatmapNeverThrows"
  "StoredStateFuzzTest arbitraryCountersNeverThrow"
)

selected=0
for target in "${TARGETS[@]}"; do
  if [ "$#" -gt 0 ]; then
    match=false
    for filter in "$@"; do [[ "$target" == *"$filter"* ]] && match=true; done
    $match || continue
  fi
  selected=$((selected + 1))
  read -r class method <<<"$target"
  echo "=== Fuzzing : $class.$method"
  JAZZER_FUZZ=1 ./gradlew :engine:test --tests "nextglyph.engine.fuzz.${class}*.${method}" --rerun --console=plain
done
[ "$selected" -gt 0 ] || { echo "Aucune cible ne correspond à : $*" >&2; exit 1; }
