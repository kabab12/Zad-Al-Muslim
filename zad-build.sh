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
for J in SilentReceiver AudioShade AudioStopReceiver ZadFiles KeepService AdhanScreen AdhanScreenReceiver AdhanService DlService ZadDl; do
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
# vibration for tasbih / adhkar counters (native ZadVib bridge in MainActivity)
perm VIBRATE
# lets the app ask the user to exempt it from battery optimisation (adhan while the app is closed)
perm REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
# "display over other apps" row in the in-app permissions screen
perm SYSTEM_ALERT_WINDOW
# Do Not Disturb access (granted by the user from system settings; the app opens that screen)
perm ACCESS_NOTIFICATION_POLICY
# exact alarms + boot: needed by the native prayer-silent scheduler in BOTH variants
perm USE_EXACT_ALARM
perm SCHEDULE_EXACT_ALARM
perm RECEIVE_BOOT_COMPLETED
# permanent downloads: Quran audio / tafsir / adhan are saved in /sdcard/ZadAlMuslim so they survive uninstall.
# Android 11+: "All files access"; Android 10 and below: classic storage permission.
perm MANAGE_EXTERNAL_STORAGE
perm WRITE_EXTERNAL_STORAGE
perm READ_EXTERNAL_STORAGE
perm WAKE_LOCK
# adhan panel over the lock screen (full-screen intent; Android 14+: user allows it from app settings)
perm USE_FULL_SCREEN_INTENT
perm FOREGROUND_SERVICE
perm FOREGROUND_SERVICE_MEDIA_PLAYBACK
perm FOREGROUND_SERVICE_DATA_SYNC
perm ACCESS_NETWORK_STATE
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
if "AdhanScreenReceiver" not in m:
    add += """        <receiver android:name=".AdhanScreenReceiver" android:exported="false">
            <intent-filter>
                <action android:name="com.zadalmuslim.app.ADHAN_SCREEN" />
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
                <action android:name="android.intent.action.TIME_SET" />
                <action android:name="android.intent.action.TIMEZONE_CHANGED" />
            </intent-filter>
        </receiver>
"""
if "KeepService" not in m:
    add += '        <service android:name=".KeepService" android:exported="false" android:foregroundServiceType="mediaPlayback" />\n'
if "DlService" not in m:
    add += '        <service android:name=".DlService" android:exported="false" android:foregroundServiceType="dataSync" />\n'
if "AdhanService" not in m:
    add += '        <service android:name=".AdhanService" android:exported="false" android:foregroundServiceType="mediaPlayback" />\n'
if "AudioStopReceiver" not in m:
    add += '        <receiver android:name=".AudioStopReceiver" android:exported="false" />\n'
if add:
    assert "</application>" in m
    m = m.replace("</application>", add + "    </application>", 1)
io.open(p, "w", encoding="utf-8").write(m)
PY
grep -c "Receiver" "$M" || true

# Android 10: keep classic file access so /sdcard/ZadAlMuslim works (ignored on 11+, where All files access is used)
python3 - "$M" <<'PY'
import sys, io
p = sys.argv[1]
m = io.open(p, encoding="utf-8").read()
if "requestLegacyExternalStorage" not in m:
    assert "<application" in m
    m = m.replace("<application", '<application android:requestLegacyExternalStorage="true"', 1)
io.open(p, "w", encoding="utf-8").write(m)
PY
grep -c "requestLegacyExternalStorage" "$M" || true

# ---- 4b) notification sounds as raw resources ----
# Channel sounds (adhan_*.ogg, snd_*.mp3) must exist in res/raw, otherwise the
# adhan / adhkar notifications arrive in the shade WITHOUT sound.
mkdir -p "$RES/raw"
node - "$RES/raw" <<'JS'
const fs=require('fs'),vm=require('vm'),path=require('path');
const out=process.argv[2];let n=0;
function save(name,b64){
  b64=String(b64||'').replace(/^data:[^,]*,/,'');
  const buf=Buffer.from(b64,'base64');
  if(buf.length<1000){console.log('skip tiny',name);return}
  if(!/^[a-z0-9_]+$/.test(name)){console.log('skip bad name',name);return}
  const h=buf.slice(0,4).toString('latin1');
  const ext=h==='OggS'?'ogg':(h==='RIFF'?'wav':'mp3');
  fs.writeFileSync(path.join(out,name+'.'+ext),buf);n++;
}
try{
  const code=fs.readFileSync('www/adhan-data.js','utf8');
  const ctx=vm.createContext({window:{},self:{}});
  const E=vm.runInContext(code+'\n;(typeof ADEMB!=="undefined"?ADEMB:(window.ADEMB||null))',ctx);
  if(E)Object.keys(E).forEach(k=>save('adhan_'+k,E[k]));
}catch(e){console.log('WARN adhan-data.js:',e.message)}
try{
  const app=fs.readFileSync('www/app.js','utf8');
  const m=app.match(/const NTA=(\{[^\n]*\});?/);
  if(m){const A=vm.runInContext('('+m[1]+')',vm.createContext({}));Object.keys(A).forEach(k=>save('snd_'+k,A[k]))}
}catch(e){console.log('WARN NTA:',e.message)}
console.log('raw sounds written:',n);
JS
ls -la "$RES/raw" || true

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

# ---- 5b) splash screen: Zad logo on green (replaces Capacitor's default blue logo) ----
mkdir -p "$RES/drawable" "$RES/values"
# remove Capacitor's default splash images (all densities / orientations)
find "$RES" -type f \( -name "splash.png" -o -name "splash.jpg" -o -name "splash.webp" \) -delete
# logo: app icon centred on a transparent canvas, so Android 12+ circle crop keeps it whole
$CMD -size 1024x1024 xc:none \( www/icon-512.png -resize 600x600 \) -gravity center -composite "$RES/drawable/zad_splash_logo.png"
cat > "$RES/values/zad_splash_colors.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="zad_splash_bg">#083f2f</color>
</resources>
XML
# pre-Android-12 / fallback window background: green + centred logo
cat > "$RES/drawable/zad_splash.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:drawable="@color/zad_splash_bg" />
    <item android:gravity="center" android:width="160dp" android:height="160dp">
        <bitmap android:src="@drawable/zad_splash_logo" android:gravity="fill" />
    </item>
</layer-list>
XML
python3 - "$RES/values/styles.xml" <<'PY'
import sys, io, re
p = sys.argv[1]
s = io.open(p, encoding="utf-8").read()
m = re.search(r'<style\s+name="AppTheme\.NoActionBarLaunch"\s+parent="([^"]+)"\s*>.*?</style>', s, re.S)
assert m, "AppTheme.NoActionBarLaunch not found in styles.xml"
parent = m.group(1)
if "SplashScreen" in parent:
    new = f'''<style name="AppTheme.NoActionBarLaunch" parent="{parent}">
        <item name="windowSplashScreenBackground">@color/zad_splash_bg</item>
        <item name="windowSplashScreenAnimatedIcon">@drawable/zad_splash_logo</item>
        <item name="postSplashScreenTheme">@style/AppTheme.NoActionBar</item>
        <item name="android:background">@drawable/zad_splash</item>
    </style>'''
else:
    new = f'''<style name="AppTheme.NoActionBarLaunch" parent="{parent}">
        <item name="android:background">@drawable/zad_splash</item>
        <item name="android:windowBackground">@drawable/zad_splash</item>
    </style>'''
s = s[:m.start()] + new + s[m.end():]
io.open(p, "w", encoding="utf-8").write(s)
print("splash style patched, parent =", parent)
PY
grep -rn "drawable/splash" "$RES" android/app/src/main/AndroidManifest.xml || echo "no stale splash refs"

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
