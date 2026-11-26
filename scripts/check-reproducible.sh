#!/usr/bin/env bash
# Vérifie que l'APK de release est reproductible (spec §18.1) : deux builds sans cache de build,
# depuis deux dossiers différents, doivent produire un APK non signé identique à l'octet près.
# Prérequis : SDK Android (local.properties) ; données de tools/build/data réutilisées si présentes.
# Si REPRO_JAVA_HOME_2 est défini, le second build tourne avec ce JDK : F-Droid construit avec le JDK de sa
# distribution (OpenJDK 21 de Debian), la release avec Temurin 25 ; leurs APK doivent rester identiques.
# Les APK sont laissés dans $RUNNER_TEMP/reproductible (ou un dossier temporaire) pour analyse.
set -euo pipefail

root="$(git rev-parse --show-toplevel)"
out="${RUNNER_TEMP:-$(mktemp -d)}/reproductible"
apk="ime/build/outputs/apk/fdroid/release/ime-fdroid-release-unsigned.apk"
rm -rf "$out"
mkdir -p "$out"

# Sans clé : l'APK de release n'est pas signé, c'est lui que F-Droid compare (variante fdroid, §18.1).
unset NEXTGLYPH_KEYSTORE NEXTGLYPH_KEYSTORE_PASSWORD NEXTGLYPH_KEY_ALIAS NEXTGLYPH_KEY_PASSWORD

build() {
    (
        cd "$1"
        if [ -n "${2:-}" ]; then
            export JAVA_HOME="$2"
            export PATH="$JAVA_HOME/bin:$PATH"
        fi
        java -version 2>&1 | head -1
        ./gradlew :ime:assembleFdroidRelease --no-build-cache --no-configuration-cache --console=plain
    )
}

# Second dossier, préparé avant le premier build : fichiers suivis par git, SDK et données téléchargées.
second="$(mktemp -d)/nextglyph-bis"
mkdir -p "$second"
(cd "$root" && git ls-files -z | xargs -0 cp -p --parents -t "$second")
[ -f "$root/local.properties" ] && cp "$root/local.properties" "$second/"
if [ -d "$root/tools/build/data" ]; then
    mkdir -p "$second/tools/build"
    cp -r "$root/tools/build/data" "$second/tools/build/"
fi

echo "== Build 1 : $root"
build "$root"
cp "$root/$apk" "$out/build-1.apk"

echo "== Build 2 : $second"
build "$second" "${REPRO_JAVA_HOME_2:-}"
cp "$second/$apk" "$out/build-2.apk"

(cd "$out" && sha256sum build-1.apk build-2.apk)
if cmp -s "$out/build-1.apk" "$out/build-2.apk"; then
    echo "APK de release reproductible."
    exit 0
fi

echo "::error::Les deux APK de release diffèrent (spec §18.1). Entrées dont le CRC ou la taille change :"
diff <(unzip -lv "$out/build-1.apk" | awk 'NR>3 {print $8, $7, $1}' | sort) \
     <(unzip -lv "$out/build-2.apk" | awk 'NR>3 {print $8, $7, $1}' | sort) || true
exit 1
