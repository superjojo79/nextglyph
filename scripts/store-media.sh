#!/usr/bin/env bash
# Produit les visuels bruts des stores sur l'appareil ou l'émulateur connecté (adb) : captures d'écran
# (StoreMediaTest, tests instrumentés) et enregistrement vidéo (scénario video_scenario filmé par screenrecord).
# L'appareil doit être dans la langue voulue (fr-FR ou en-US) : la locale système choisit les textes et la disposition.
#
# Usage : scripts/store-media.sh <dossier-de-sortie>
# Résultat : <dossier-de-sortie>/<locale>/NN-*.png et promo.mp4
set -euo pipefail

if [ $# -ne 1 ]; then
  echo "Usage : $0 <dossier-de-sortie>" >&2
  exit 2
fi
out_root="$1"

app_id="io.github.superjojo79.nextglyph"
test_id="${app_id}.test"
runner="${test_id}/androidx.test.runner.AndroidJUnitRunner"
remote_media="/data/local/tmp/store-media"
remote_video="/sdcard/promo.mp4"
test_class='nextglyph.ime.StoreMediaTest'
video_test='nextglyph.ime.StoreMediaTest$HappyPath#video_scenario'

cd "$(dirname "$0")/.."

adb wait-for-device
adb shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 1; done'

# --- Installation --------------------------------------------------------------------------------------------
app_apk="$(ls ime/build/outputs/apk/debug/*.apk 2>/dev/null | head -n 1 || true)"
test_apk="$(ls ime/build/outputs/apk/androidTest/debug/*.apk 2>/dev/null | head -n 1 || true)"
if [ -n "$app_apk" ] && [ -n "$test_apk" ]; then
  echo "Installation de $app_apk et $test_apk"
  adb install -r -t -g "$app_apk"
  adb install -r -t -g "$test_apk"
else
  echo "APK introuvables : installation par Gradle"
  ./gradlew :ime:installDebug :ime:installDebugAndroidTest --console=plain
fi

# --- Locale (dossier de sortie) -----------------------------------------------------------------------------
locale="$(adb shell getprop persist.sys.locale | tr -d '\r')"
[ -n "$locale" ] || locale="$(adb shell getprop ro.product.locale | tr -d '\r')"
[ -n "$locale" ] || locale="inconnue"
dest="${out_root}/${locale}"
mkdir -p "$dest"
echo "Locale de l'appareil : $locale -> $dest"

# --- Appareil propre : écran déverrouillé, animations coupées, barre d'état de démonstration ------------------
adb shell input keyevent 82 || true
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0

demo() { adb shell am broadcast -a com.android.systemui.demo "$@" > /dev/null; }
adb shell settings put global sysui_demo_allowed 1
demo -e command enter
demo -e command clock -e hhmm 0941
demo -e command battery -e level 100 -e plugged false
demo -e command network -e wifi show -e level 4 -e fully true
demo -e command network -e mobile hide
demo -e command notifications -e visible false
demo -e command status -e volume hide
# En sortie, même en échec : barre d'état normale et récupération de ce qui a été produit (diagnostic).
finish() {
  demo -e command exit || true
  adb pull "${remote_media}/." "$dest/" > /dev/null 2>&1 || true
}
trap finish EXIT

adb shell rm -rf "$remote_media" "$remote_video"

# --- Captures (tout sauf la vidéo) ---------------------------------------------------------------------------
echo "Captures d'écran"
instrument_log="$(mktemp)"
timeout 900 adb shell am instrument -w -r \
  -e storeMedia true \
  -e class "$test_class" \
  -e notClass "$video_test" \
  "$runner" | tee "$instrument_log" || { echo "Tests de captures interrompus (délai dépassé ?)" >&2; exit 1; }
if grep -q -E 'INSTRUMENTATION_RESULT: shortMsg=|INSTRUMENTATION_STATUS_CODE: -[12]\s*$' "$instrument_log"; then
  echo "Échec des tests de captures (voir le journal ci-dessus)." >&2
  exit 1
fi

# --- Vidéo ---------------------------------------------------------------------------------------------------
echo "Enregistrement vidéo"
adb shell screenrecord --bit-rate 8000000 --time-limit 60 "$remote_video" &
record_pid=$!
sleep 3
video_status=0
timeout 300 adb shell am instrument -w -r \
  -e storeMedia true \
  -e class "$video_test" \
  "$runner" | tee "$instrument_log" || video_status=$?
# Arrêt propre : SIGINT laisse screenrecord finaliser le fichier MP4.
adb shell 'kill -2 $(pidof screenrecord)' || true
wait "$record_pid" || true
sleep 2
if [ "$video_status" -ne 0 ] || grep -q -E 'INSTRUMENTATION_RESULT: shortMsg=|INSTRUMENTATION_STATUS_CODE: -[12]\s*$' "$instrument_log"; then
  echo "Échec du scénario vidéo (voir le journal ci-dessus)." >&2
  exit 1
fi

# --- Récupération --------------------------------------------------------------------------------------------
adb pull "${remote_media}/." "$dest/" || true
adb pull "$remote_video" "$dest/promo.mp4" || true

shopt -s nullglob
captures=("$dest"/*.png)
if [ "${#captures[@]}" -eq 0 ]; then
  echo "Aucune capture produite dans $dest : le test a été ignoré ou n'a rien écrit." >&2
  exit 1
fi
if [ ! -s "$dest/promo.mp4" ]; then
  echo "Aucune vidéo produite dans $dest." >&2
  exit 1
fi
echo "${#captures[@]} capture(s) et une vidéo dans $dest"
ls -l "$dest"
