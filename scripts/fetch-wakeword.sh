#!/usr/bin/env bash
# Fetches the "Hey Ubiq" wake word engine into android/app/wakeword/ (gitignored):
# sherpa-onnx's Android JNI library (onnxruntime linked in, arm64 only) and its small English
# keyword-spotting model (zipformer, 3.3M params, int8). Both are Apache-2.0, from
# https://github.com/k2-fsa/sherpa-onnx/releases. The keyword list itself is ours:
# android/app/src/main/assets/wakeword/keywords.txt.
set -euo pipefail
cd "$(dirname "$0")/../android/app"
OUT=wakeword
VER=v2.0.1
LIB_TAR=sherpa-onnx-$VER-android-static-link-onnxruntime.tar.bz2
LIB_SHA=fe1c9ceffd86770218a1d4bba6b410d6f726b2b8dda6a5488499b05250b53aea
MODEL=sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01
MODEL_SHA=f170013b4716e41b62b9bfd809687c207cef798ef9bc6534d524e17af9b6561a
STAMP="$VER $MODEL"

if [ -f $OUT/.stamp ] && [ "$(cat $OUT/.stamp)" = "$STAMP" ]; then exit 0; fi
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
get() { # url sha file
  curl -fsSL --retry 3 -o "$TMP/$3" "$1"
  echo "$2  $TMP/$3" | shasum -a 256 -c - >/dev/null || { echo "checksum mismatch: $3" >&2; exit 1; }
}
get "https://github.com/k2-fsa/sherpa-onnx/releases/download/$VER/$LIB_TAR" $LIB_SHA lib.tar.bz2
get "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/$MODEL.tar.bz2" $MODEL_SHA model.tar.bz2
tar -xjf "$TMP/lib.tar.bz2" -C "$TMP" ./jniLibs/arm64-v8a/libsherpa-onnx-jni.so
tar -xjf "$TMP/model.tar.bz2" -C "$TMP"

rm -rf $OUT; mkdir -p $OUT/jniLibs/arm64-v8a $OUT/assets/wakeword
cp "$TMP/jniLibs/arm64-v8a/libsherpa-onnx-jni.so" $OUT/jniLibs/arm64-v8a/
M="$TMP/$MODEL"
cp "$M/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx" $OUT/assets/wakeword/encoder.onnx
cp "$M/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx" $OUT/assets/wakeword/decoder.onnx
cp "$M/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx" $OUT/assets/wakeword/joiner.onnx
cp "$M/tokens.txt" $OUT/assets/wakeword/tokens.txt
echo "$STAMP" > $OUT/.stamp
echo "wake word engine: $(du -sh $OUT | cut -f1) in android/app/$OUT"
