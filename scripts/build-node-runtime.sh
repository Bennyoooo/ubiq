#!/usr/bin/env bash
# Builds the on-phone Pi runtime (docs/AGENT_DESIGN.md, migration step 4) into android/app/node-runtime/ (git-ignored):
#
#   jniLibs/arm64-v8a/libnode_runtime.so   Termux's Node 24 (bionic, arm64) plus its shared libraries, renamed
#   jniLibs/arm64-v8a/libnr_*.so           to lib*.so so the APK installer extracts them into nativeLibraryDir,
#                                          the one app directory Android lets a process exec from.
#   assets/node/cert.pem                   CA bundle (Termux's OpenSSL looks under /data/data/com.termux otherwise).
#   assets/node/harness.zip                harness/ with production node_modules, pruned (no maps, types, docs).
#   assets/node/version                    hash of all of the above; the app re-extracts when it changes.
#
# The Gradle build runs this when an input changes. Needs: curl, patchelf (brew install patchelf), npm, zip.
# Termux keeps only current package versions in its pool: when a pinned .deb is gone, bump the versions and
# hashes below from https://packages-cf.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/android/app/node-runtime"
CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/agentos/termux-debs"
REPO=https://packages-cf.termux.dev/apt/termux-main

DEBS=(
  "pool/main/n/nodejs-lts/nodejs-lts_24.22.1_aarch64.deb c4aae57009e4398613807d77ff395026455fe03b6d9d8a29f929212b04f74719"
  "pool/main/libc/libc++/libc++_30_aarch64.deb 53d0b84a7ba7459024257cb94d5b136fe13ef858567f65a8064b35950799f2ca"
  "pool/main/o/openssl/openssl_1:3.6.5_aarch64.deb a2fb7856875e63ef6b90442752e05974813372c3ca86d2513a78ea43c67a0f8b"
  "pool/main/c/c-ares/c-ares_1.34.9_aarch64.deb 623d84855e176f3f2a415d24c0e1d2f529356f4b5439bb3313e6d7ff0da4585c"
  "pool/main/libi/libicu/libicu_78.3_aarch64.deb f536403f65a08fe0df6e7304184e902d54def77d5c3bd5edfd9109d57601d276"
  "pool/main/libs/libsqlite/libsqlite_3.54.1_aarch64.deb 0856cac8cc87f6e112f2eef4fcac6ad5cb08209ecb7cc260f997874fc186a3c0"
  "pool/main/z/zlib/zlib_1.3.2_aarch64.deb 75e7d0af17fcc3b40004309fdc00a1ddb9ae08346dce5e269902c34ac3966ac9"
  "pool/main/c/ca-certificates/ca-certificates_1:2026.10.20-1_all.deb ab8a7fe63293b455c02399ee125eb8a0041e430ccfdf89a5be8317522fdf9107"
)

# Termux file -> name in the APK. Order matters only for readability.
LIBS=(
  "libz.so.1.3.2 libnr_z.so libz.so.1"
  "libcares.so libnr_cares.so libcares.so"
  "libsqlite3.so.3.54.1 libnr_sqlite3.so libsqlite3.so"
  "libcrypto.so.3 libnr_crypto.so libcrypto.so.3"
  "libssl.so.3 libnr_ssl.so libssl.so.3"
  "libicudata.so.78.3 libnr_icudata.so libicudata.so.78"
  "libicuuc.so.78.3 libnr_icuuc.so libicuuc.so.78"
  "libicui18n.so.78.3 libnr_icui18n.so libicui18n.so.78"
  "libc++_shared.so libnr_cxx.so libc++_shared.so"
)

command -v patchelf >/dev/null || { echo "patchelf is missing: brew install patchelf" >&2; exit 1; }

WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
mkdir -p "$CACHE" "$WORK/root"
for entry in "${DEBS[@]}"; do
  read -r path sha <<<"$entry"
  deb="$CACHE/$(basename "$path")"
  [ -f "$deb" ] || curl -sSfL -o "$deb" "$REPO/$path"
  echo "$sha  $deb" | shasum -a 256 -c --quiet - || { echo "hash mismatch: $deb" >&2; rm -f "$deb"; exit 1; }
  tar -xOf "$deb" data.tar.xz | tar -xJf - -C "$WORK/root"
done
USR="$WORK/root/data/data/com.termux/files/usr"

LIBDIR="$WORK/out/jniLibs/arm64-v8a"; mkdir -p "$LIBDIR" "$WORK/out/assets/node"
patch_needed() {   # rewrite every dependency on a Termux library to its renamed copy; resolve next to ourselves
  local f="$1"
  for l in "${LIBS[@]}"; do read -r _ new soname <<<"$l"; patchelf --replace-needed "$soname" "$new" "$f"; done
  patchelf --set-rpath '$ORIGIN' "$f"
}
cp "$USR/bin/node" "$LIBDIR/libnode_runtime.so"; patch_needed "$LIBDIR/libnode_runtime.so"
for l in "${LIBS[@]}"; do
  read -r src new _ <<<"$l"
  cp -L "$USR/lib/$src" "$LIBDIR/$new"
  patchelf --set-soname "$new" "$LIBDIR/$new"
  patch_needed "$LIBDIR/$new"
done
cp "$USR/etc/tls/cert.pem" "$WORK/out/assets/node/cert.pem"

# The harness: production dependencies only, without what Node never reads at run time.
H="$WORK/harness"; mkdir -p "$H"
cp -R "$ROOT/harness/server.mjs" "$ROOT/harness/package.json" "$ROOT/harness/package-lock.json" "$H/"
[ -d "$ROOT/harness/apps" ] && cp -R "$ROOT/harness/apps" "$H/"
(cd "$H" && npm ci --omit=dev --ignore-scripts --no-audit --no-fund --loglevel=error)
find "$H/node_modules" \( -name "*.map" -o -name "*.d.ts" -o -name "*.d.mts" -o -name "*.d.cts" -o -name "*.md" \
  -o -name "*.markdown" -o -name "CHANGELOG*" -o -name "*.tsbuildinfo" \) -type f -delete
rm -rf "$H/node_modules/@types" "$H/node_modules/@earendil-works/pi-tui/native" \
  "$H/node_modules/@earendil-works/pi-coding-agent/examples" "$H/node_modules/@earendil-works/pi-coding-agent/docs"
(cd "$H" && zip -qr -X "$WORK/out/assets/node/harness.zip" .)

(cd "$WORK/out" && find . -type f ! -name version | sort | xargs shasum -a 256 | shasum -a 256 | cut -c1-16) > "$WORK/out/assets/node/version"
rm -rf "$OUT"; mkdir -p "$(dirname "$OUT")"; mv "$WORK/out" "$OUT"
echo "node runtime: $(du -sh "$OUT/jniLibs" | cut -f1) libs, harness.zip $(du -h "$OUT/assets/node/harness.zip" | cut -f1), version $(cat "$OUT/assets/node/version")"
