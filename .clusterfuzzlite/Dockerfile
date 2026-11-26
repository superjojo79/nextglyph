# Image de construction ClusterFuzzLite pour la JVM (Jazzer fourni), épinglée par empreinte.
FROM gcr.io/oss-fuzz-base/base-builder-jvm@sha256:f34c2b102970b3c048d9ce77ee21cc16c627747e2c3b05f2dfbf3da221047496
COPY . $SRC/nextglyph
WORKDIR $SRC/nextglyph
COPY .clusterfuzzlite/build.sh $SRC/
