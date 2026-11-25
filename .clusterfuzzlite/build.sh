#!/bin/bash -eu
# Construction ClusterFuzzLite : compile le moteur et ses tests, puis crée un lanceur Jazzer par cible
# de nextglyph.engine.fuzz.ClusterFuzzTargets (mêmes propriétés que les @FuzzTest, docs/TESTING.md).

# Noms de tests accentués (fichiers .class) : l'image n'a pas de locale UTF-8 par défaut.
export LANG=C.UTF-8 LC_ALL=C.UTF-8

# Le module ime exige le SDK Android, absent de l'image : on le retire de cette copie des sources.
sed -i '/include(":ime")/d' settings.gradle.kts
sed -i '/libs.plugins.android.application/d' build.gradle.kts

./gradlew --no-daemon --no-configuration-cache --console=plain \
  --init-script .clusterfuzzlite/classpath.init.gradle :engine:writeFuzzClasspath

# Classpath copié dans $OUT ; le pilote apporte sa propre API Jazzer, on n'embarque pas celle de Maven.
mkdir -p "$OUT/classpath"
runtime=""
index=0
while IFS= read -r entry; do
  [ -e "$entry" ] || continue
  name=$(basename "$entry")
  case "$name" in jazzer-*) continue ;; esac
  index=$((index + 1))
  target="classpath/$index-$name"
  cp -r "$entry" "$OUT/$target"
  runtime="$runtime:\$this_dir/$target"
done < engine/build/fuzz-classpath.txt
runtime=${runtime#:}

for target in Lexicon NgramModel LearnedDataBytes LearnedDataSections LearnedDataStream \
              ByteReader ReadAtMost Heatmap Counters; do
  launcher="$OUT/$target"
  cat > "$launcher" <<LAUNCHER
#!/bin/bash
# LLVMFuzzerTestOneInput : marqueur de détection des cibles par ClusterFuzzLite.
this_dir=\$(dirname "\$0")
if [[ "\$@" =~ (^| )-runs=[0-9]+(\$| ) ]]; then
  mem_settings='-Xmx1900m:-Xss900k'
else
  mem_settings='-Xmx2048m:-Xss1024k'
fi
LD_LIBRARY_PATH="$JVM_LD_LIBRARY_PATH":\$this_dir \\
\$this_dir/jazzer_driver --agent_path=\$this_dir/jazzer_agent_deploy.jar \\
  --cp=$runtime \\
  --target_class='nextglyph.engine.fuzz.ClusterFuzzTargets\$$target' \\
  --jvm_args="\$mem_settings" \\
  \$@
LAUNCHER
  chmod +x "$launcher"
done
