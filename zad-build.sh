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
# @capacitor/app: required for the Android back button to work inside the app
npm install --no-audit --no-fund @capacitor/status-bar@^6.0.1 @capacitor/app@^6.0.0

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

  # إصلاح زر تشغيل/إيقاف في إشعار الوسائط: الإضافة تمرّر قيمة مركّبة (516/514) فيصبح الـ PendingIntent null
  python3 fix-media-buttons.py
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

# native bridge for "silent during prayer" (Do Not Disturb) - registered in MainActivity
AS=AndroidSilent.java
if [ -f native/AndroidSilent.java ]; then AS=native/AndroidSilent.java; fi
test -f "$AS"
cp "$AS" "$(dirname "$F")/AndroidSilent.java"

# native: scheduled silent-mode receiver + notification-shade "stop" button for Quran/Hisn audio
for J in SilentReceiver AudioShade AudioStopReceiver; do
  JS_SRC="$J.java"
  if [ -f "native/$J.java" ]; then JS_SRC="native/$J.java"; fi
  test -f "$JS_SRC"
  cp "$JS_SRC" "$(dirname "$F")/$J.java"
done

# ---- 4) permissions ----
perm() {
  if ! grep -q "android.permission.$1\"" "$M"; then
    sed -i "s#</manifest>#    <uses-permission android:name=\"android.permission.$1\" />\n</manifest>#" "$M"
  fi
}
perm ACCESS_FINE_LOCATION
perm ACCESS_COARSE_LOCATION
perm POST_NOTIFICATIONS
# Do Not Disturb access (granted by the user from system settings; the app opens that screen)
perm ACCESS_NOTIFICATION_POLICY
# exact alarms + boot: needed by the native prayer-silent scheduler in BOTH variants
perm USE_EXACT_ALARM
perm SCHEDULE_EXACT_ALARM
perm RECEIVE_BOOT_COMPLETED
if [ "$MODE" = "full" ]; then
  perm WAKE_LOCK
  perm FOREGROUND_SERVICE
  perm FOREGROUND_SERVICE_MEDIA_PLAYBACK
fi
grep -c "uses-permission" "$M" || true

# receivers: prayer-silent alarms/boot + audio stop button
python3 - "$M" <<'PY'
import sys, io
p = sys.argv[1]
m = io.open(p, encoding="utf-8").read()
add = ""
if "SilentReceiver" not in m:
    add += """        <receiver android:name=".SilentReceiver" android:exported="false">
            <intent-filter>
                <action android:name="com.zadalmuslim.app.SILENT_TICK" />
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
                <action android:name="android.intent.action.TIME_SET" />
                <action android:name="android.intent.action.TIMEZONE_CHANGED" />
            </intent-filter>
        </receiver>
"""
if "AudioStopReceiver" not in m:
    add += '        <receiver android:name=".AudioStopReceiver" android:exported="false" />\n'
if add:
    assert "</application>" in m
    m = m.replace("</application>", add + "    </application>", 1)
io.open(p, "w", encoding="utf-8").write(m)
PY
grep -c "Receiver" "$M" || true

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
