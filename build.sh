#!/usr/bin/env bash
# Nina Pocket's APK, without Android Studio or Gradle: gomobile bind (fsttm-live's bridge + Nina's
# package), then the SDK's build tools (aapt2, javac, d8, zipalign, apksigner).
#
#   ./build.sh [arm32|arm64] [--install]     → out/nina-pocket-<variant>.apk
#
# arm32: 32-bit ARM, Android 7.1+ (the Fire 7, LineageOS 14.1): minSdk 25, targetSdk 25.
# arm64: 64-bit ARM, Android 8+ (newer phones): minSdk 26, targetSdk 34.
#
# The checkouts beside this one (REPO_ROOT, default ..): fsttm-live (the bridge, pkg/mobile),
# nina-live (Nina: mobile/, prompts/, catalog/), acasa (her face: ha-ui/www/nina),
# perotheia-packages/metis-lang (libmetis_rt; metisc when the catalog's image is not built).
# Env: ANDROID_HOME (~/Android/Sdk), ANDROID_NDK_HOME (its newest ndk), KEYSTORE / KEYSTORE_PASS /
# KEY_ALIAS (default the SDK's debug key), VERSION_CODE (default the commits on HEAD).
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
variant=${1:-arm32}; [ "${1:-}" ] && shift
install=; [ "${1:-}" = --install ] && install=1
case "$variant" in
  arm32) GOTARGET=android/arm   API=25 MIN=25 TARGET=25 ABI=armeabi-v7a CLANG=armv7a-linux-androideabi25 ;;
  arm64) GOTARGET=android/arm64 API=26 MIN=26 TARGET=34 ABI=arm64-v8a   CLANG=aarch64-linux-android26 ;;
  *) echo "variant: arm32 or arm64" >&2; exit 2 ;;
esac
REPO_ROOT="${REPO_ROOT:-$(cd "$here/.." && pwd)}"
FSTTM="$REPO_ROOT/fsttm-live" NINA="$REPO_ROOT/nina-live" ACASA="$REPO_ROOT/acasa"
METIS_LANG_DIR="${METIS_LANG_DIR:-$REPO_ROOT/perotheia-packages/metis-lang}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$(ls -d "$ANDROID_HOME"/ndk/* | sort -V | tail -1)}"
TC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
BT=$(ls -d "$ANDROID_HOME"/build-tools/* | grep -v rc | sort -V | tail -1)
JAR=$(ls -d "$ANDROID_HOME"/platforms/android-* | sort -V | tail -1)/android.jar
out="$here/out/$variant"; rm -rf "$out"; mkdir -p "$out/classes" "$out/apk/lib/$ABI" "$out/assets/nina"
cache="$here/out/cache/$variant"; mkdir -p "$cache"
gomobile=$(command -v gomobile || echo "$HOME/go/bin/gomobile")

echo "== libmetis_rt ($ABI)"
rt="$METIS_LANG_DIR/runtime"
"$TC/$CLANG-clang++" -std=c++17 -O2 -fPIC -I"$rt/include" -I"$rt/c" -c "$rt/c/metis_rt.cpp" -o "$cache/metis_rt.o"
"$TC/llvm-ar" rcs "$cache/libmetis_rt.a" "$cache/metis_rt.o"
# -lm: libc++ and the runtime use libm (Android 7's libc still exported pow; Android 17's does not: the
# Pixel 7 refused the library, "cannot locate symbol pow"); 16 KB pages: Android 15+ devices may use
# them, and warn about (or refuse) a library aligned for 4 KB
export CGO_CFLAGS="-I$rt/c" CGO_LDFLAGS="-L$cache -lmetis_rt -lc++_static -lc++abi -lm -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"

echo "== gomobile bind ($GOTARGET, API $API)"
# gomobile reads go.mod's replaces, not a go.work: for the build only, nina-live's go.mod gets one for
# the fsttm-live checkout and one for a stub of whisper.cpp's Go bindings (fsttm/go requires them at
# v0.0.0 through its own local replace; nothing bound imports them); go.mod and go.sum restored on exit
export GOWORK=off GOTOOLCHAIN="${GOTOOLCHAIN_BIND:-go1.26.11}"
cp "$NINA/go.mod" "$cache/go.mod.keep"; cp "$NINA/go.sum" "$cache/go.sum.keep"
trap 'cp "$cache/go.mod.keep" "$NINA/go.mod"; cp "$cache/go.sum.keep" "$NINA/go.sum"' EXIT
mkdir -p "$cache/whisper-stub" && printf 'module github.com/ggerganov/whisper.cpp/bindings/go\n\ngo 1.22\n' > "$cache/whisper-stub/go.mod"
(cd "$NINA" && go mod edit -replace "github.com/nina-ai-dev/fsttm-live=$FSTTM" \
   -replace "github.com/ggerganov/whisper.cpp/bindings/go=$cache/whisper-stub" && go mod tidy > /dev/null 2>&1 || true)
(cd "$NINA" && PATH="$HOME/go/bin:$PATH" "$gomobile" bind -target=$GOTARGET -androidapi $API -javapkg live.fsttm \
  -o "$out/bridge.aar" github.com/nina-ai-dev/fsttm-live/pkg/mobile github.com/nina-ai-dev/nina-live/mobile)
(cd "$out" && unzip -q -o bridge.aar classes.jar "jni/$ABI/*" && cp jni/$ABI/*.so "apk/lib/$ABI/")

echo "== her files (assets)"
# the composition's image pinned by image.golden, built as nina-live's build.sh does (an image left in build/
# from an older catalog is not it: 2026-11-28, the tablet shipped one without the temperament package); the
# images it superseded go (Nina refuses to start with two)
gold="v-$(cat "$NINA/catalog/domains/image.golden")"
if [ ! -d "$NINA/catalog/domains/build/home.image.$gold" ]; then
  METISC="${METISC:-$METIS_LANG_DIR/src/metisc}"
  make -s -C "$METIS_LANG_DIR/src" metisc   # incremental: a stale compiler refuses newer catalogs
  built=$(cd "$NINA/catalog/domains" && "$METISC" build . | grep -o '^image home v-[0-9a-f]*' | grep -o 'v-[0-9a-f]*' | head -1)
  [ "$built" = "$gold" ] || { echo "image $built is not image.golden's ($gold)" >&2; exit 1; }
fi
for old in "$NINA"/catalog/domains/build/home.image.v-*; do [ "$old" = "$NINA/catalog/domains/build/home.image.$gold" ] || rm -rf "$old"; done
cp -rL "$NINA/catalog" "$out/assets/catalog"                    # -L: catalog/domains may be a link (the narratives' devices)
rm -rf "$out/assets/catalog/tests" "$out/assets/catalog/deploy"
cp "$NINA/mobile/home.map.json" "$out/assets/catalog/world/home.map.json"   # an empty house: no rooms, no devices (app.NoDevice)
cp "$NINA/mobile/android.yaml" "$out/assets/nina/dialogue.yaml"
cp "$NINA/mobile/nina.yaml" "$out/assets/nina/nina.yaml"
cp "$NINA/prompts/nina.md" "$NINA/prompts/voice.md" "$out/assets/nina/"
cp -rL "$ACASA/ha-ui/www/nina" "$out/assets/nina-ui"
rm -f "$out/assets/nina-ui"/_test-* "$out/assets/nina-ui/parallax-test.html"
# her plugins' cards (nina-live docs/design/plugin-cards.md): each plugin's plugin.yaml and web/, in plugins/<repo>/
# (the phone's nina.yaml lists those it uses); a plugin not checked out beside is left out
for p in nina-google-plugin nina-ha-plugin; do
  [ -f "$REPO_ROOT/$p/plugin.yaml" ] || continue
  mkdir -p "$out/assets/plugins/$p" && cp "$REPO_ROOT/$p/plugin.yaml" "$out/assets/plugins/$p/"
  [ -d "$REPO_ROOT/$p/web" ] && cp -r "$REPO_ROOT/$p/web" "$out/assets/plugins/$p/"
done

echo "== resources and manifest (aapt2)"
VERSION_CODE="${VERSION_CODE:-$(git -C "$here" rev-list --count HEAD 2>/dev/null || echo 1)}"
"$BT/aapt2" compile --dir "$here/res" -o "$out/res.zip"
"$BT/aapt2" link --manifest "$here/AndroidManifest.xml" -I "$JAR" -R "$out/res.zip" -A "$out/assets" --auto-add-overlay \
  --min-sdk-version $MIN --target-sdk-version $TARGET --version-code "$VERSION_CODE" \
  --version-name "0.1.$VERSION_CODE-$variant" -o "$out/base.apk"

echo "== java (javac, d8)"
javac -nowarn -Xlint:-options -source 8 -target 8 -bootclasspath "$JAR" -cp "$out/classes.jar" -d "$out/classes" \
  $(find "$here/src" -name '*.java')
"$BT/d8" --min-api $MIN --lib "$JAR" --output "$out/apk" $(find "$out/classes" -name '*.class') "$out/classes.jar"

echo "== apk (zip, zipalign, apksigner)"
cp "$out/base.apk" "$out/unsigned.apk"
(cd "$out/apk" && zip -q -r "$out/unsigned.apk" classes.dex lib)
"$BT/zipalign" -f -P 16 4 "$out/unsigned.apk" "$out/aligned.apk"   # -P 16: native libraries on 16 KB pages
"$BT/apksigner" sign --ks "${KEYSTORE:-$HOME/.android/debug.keystore}" --ks-pass "pass:${KEYSTORE_PASS:-android}" \
  ${KEY_ALIAS:+--ks-key-alias "$KEY_ALIAS"} --out "$here/out/nina-pocket-$variant.apk" "$out/aligned.apk"
ls -la "$here/out/nina-pocket-$variant.apk"

if [ -n "$install" ]; then adb install -r "$here/out/nina-pocket-$variant.apk"; fi
