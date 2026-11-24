#!/usr/bin/env bash
# Monte la vidéo promo (1080 × 1920, 30 i/s, H.264, sans son) à partir de l'enregistrement brut de l'émulateur.
# Usage : scripts/store-visuals/make-video.sh <dossier-brut> <locale> <fichier-sortie.mp4> [début-s] [vitesse]
#   <dossier-brut>/<locale>/promo.mp4 : branche store-media-raw. Cartons rendus par cards.js (Playwright).
# Plan : intro (logo) 2,5 s → saisie filmée, recadrée sur la conversation et le clavier, avec trois légendes
# successives → fin (logo et arguments) 4 s. Aucune musique : rien à créditer, rien sous licence non libre.
set -euo pipefail
raw="$1"; locale="$2"; out="$3"; start="${4:-3}"; speed="${5:-1.3}"
here="$(cd "$(dirname "$0")" && pwd)"
cards="$(mktemp -d)"
trap 'rm -rf -- "$cards"' EXIT
node "$here/cards.js" "$locale" "$cards" > /dev/null

src="$raw/$locale/promo.mp4"
total=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$src")
# Durée utile de la saisie, puis durée à l'écran après accélération.
body=$(python3 -c "print(max(1.0, $total - $start - 1.0))")
shown=$(python3 -c "print(round($body / $speed, 3))")
third=$(python3 -c "print(round($shown / 3, 3))")
intro=2.5; outro=4; fade=0.5
t1=$third; t2=$(python3 -c "print(2 * $third)")

ffmpeg -v error -y \
  -loop 1 -t "$intro" -i "$cards/intro.png" \
  -loop 1 -t "$shown" -i "$cards/fond.png" \
  -ss "$start" -t "$body" -i "$src" \
  -loop 1 -t "$shown" -i "$cards/legende-1.png" \
  -loop 1 -t "$shown" -i "$cards/legende-2.png" \
  -loop 1 -t "$shown" -i "$cards/legende-3.png" \
  -loop 1 -t "$outro" -i "$cards/fin.png" \
  -filter_complex "
    [2:v]setpts=(PTS-STARTPTS)/$speed,fps=30,crop=1080:1450:0:840,scale=1000:-2,pad=iw+4:ih+4:2:2:color=0x2A3B5E[rec];
    [1:v][rec]overlay=(W-w)/2:470:shortest=1[b0];
    [b0][3:v]overlay=0:0:enable='lt(t,$t1)'[b1];
    [b1][4:v]overlay=0:0:enable='between(t,$t1,$t2)'[b2];
    [b2][5:v]overlay=0:0:enable='gt(t,$t2)',fps=30,format=yuv420p,setsar=1[body];
    [0:v]fps=30,format=yuv420p,setsar=1[in];
    [6:v]fps=30,format=yuv420p,setsar=1[end];
    [in][body]xfade=transition=fade:duration=$fade:offset=$(python3 -c "print($intro - $fade)")[x1];
    [x1][end]xfade=transition=fade:duration=$fade:offset=$(python3 -c "print($intro + $shown - 2 * $fade)")[v]" \
  -map "[v]" -c:v libx264 -preset slow -crf 20 -pix_fmt yuv420p -movflags +faststart -an "$out"
echo "$out : $(ffprobe -v error -show_entries format=duration -of csv=p=0 "$out") s"
