#!/usr/bin/env bash
# Build Zad Al-Muslim APK
# Usage: bash zad-build.sh basic   or   bash zad-build.sh full
set -euo pipefail

MODE="${1:-full}"
echo "==== Zad build: [$MODE] ===="

# ---- 1) web files ----
SRC="."
if [ -f www/index.html ] && [ ! -f index.html ]; then SRC="www"; fi
rm -rf web_out && mkdir -p web_out

for f in index.html sw.js manifest.webmanifest \
         styles.css override.css native.js data-icons.js \
         app.js tafsir-data.js adhan-data.js; do
  if [ -f "$SRC/$f" ]; then
    cp "$SRC/$f" web_out/
  else
    echo "WARN missing: $f"
  fi
done
for f in "$SRC"/icon-*.png; do
  if [ -f "$f" ]; then cp "$f" web_out/; fi
done

test -f web_out/index.html
rm -rf www && mv web_out www

# ---- 2) packages ----
npm install --no-audit --no-fund
npm install --no-audit --no-fund @capacitor/status-bar@^6.0.1

if [ "$MODE" = "full" ]; then
  npm install --no-audit --no-fund \
    @capacitor/local-notifications@^6.0.0 \
    @capacitor/geolocation@^6.0.0 \
    @jofr/capacitor-media-session@^4.0.0

  PM=node_modules/@jofr/capacitor-media-session/android/src/main/AndroidManifest.xml
  if [ -f "$PM" ]; then
    if ! grep -q foregroundServiceType "$PM"; then
      sed -i 's#<service #<service android:foregroundServiceType="mediaPlayback" #' "$PM"
    fi
  fi
fi

# ---- 3) android project ----
rm -rf android
npx cap add android
RES=android/app/src/main/res
M=android/app/src/main/AndroidManifest.xml

MA=MainActivity.java
if [ -f native/MainActivity.java ]; then MA=native/MainActivity.java; fi
test -f "$MA"
F=$(find android/app/src/main/java -name MainActivity.java | head -n 1)
cp "$MA" "$F"

# ---- 4) permissions ----
perm() {
  if ! grep -q "android.permission.$1\"" "$M"; then
    sed -i "s#</manifest>#    <uses-permission android:name=\"android.permission.$1\" />\n</manifest>#" "$M"
  fi
}
perm ACCESS_FINE_LOCATION
perm ACCESS_COARSE_LOCATION
perm POST_NOTIFICATIONS
if [ "$MODE" = "full" ]; then
  perm USE_EXACT_ALARM
  perm SCHEDULE_EXACT_ALARM
  perm RECEIVE_BOOT_COMPLETED
  perm WAKE_LOCK
  perm FOREGROUND_SERVICE
  perm FOREGROUND_SERVICE_MEDIA_PLAYBACK
fi
grep -c "uses-permission" "$M" || true

# ---- 5) icons ----
sudo apt-get update -y >/dev/null
sudo apt-get install -y imagemagick >/dev/null
CMD=$(command -v magick || echo convert)
rm -rf "$RES/mipmap-anydpi-v26"
for d in mdpi:48 hdpi:72 xhdpi:96 xxhdpi:144 xxxhdpi:192; do
  n=${d%%:*}
  s=${d##*:}
  $CMD www/icon-512.png -resize ${s}x${s} "$RES/mipmap-$n/ic_launcher.png"
  $CMD www/icon-512.png -resize ${s}x${s} "$RES/mipmap-$n/ic_launcher_round.png"
done

mkdir -p "$RES/drawable"
if [ -f ic_stat_zad.xml ]; then cp ic_stat_zad.xml "$RES/drawable/ic_stat_zad.xml"; fi

# ---- 6) signing key ----
mkdir -p "$HOME/.android"
if [ -f zad-debug.keystore ]; then cp zad-debug.keystore "$HOME/.android/debug.keystore"; fi

# ---- 7) build ----
npx cap sync android
( cd android && chmod +x gradlew && ./gradlew assembleDebug --no-daemon )

mkdir -p out
cp android/app/build/outputs/apk/debug/app-debug.apk "out/zad-$MODE.apk"
ls -l out
echo "==== DONE: $MODE ===="
