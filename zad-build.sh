#!/usr/bin/env bash
# بناء APK لتطبيق زاد المسلم.   الاستعمال:  bash zad-build.sh full   أو   bash zad-build.sh basic
#  full  = كل الميزات (إشعارات الستارة، تحكم الصوت في الستارة، الموقع بإضافة رسمية)
#  basic = نسخة احتياطية مضمونة بلا إضافات جديدة
set -euo pipefail
MODE="${1:-full}"
echo "==== زاد المسلم: بناء النسخة [$MODE] ===="

# 1) ملفات الويب (تعمل سواء كانت في الجذر أو داخل www)
SRC="."; [ -f www/index.html ] && [ ! -f index.html ] && SRC="www"
rm -rf web_out && mkdir -p web_out
for f in index.html sw.js manifest.webmanifest; do [ -f "$SRC/$f" ] && cp "$SRC/$f" web_out/; done
for f in "$SRC"/icon-*.png; do [ -f "$f" ] && cp "$f" web_out/; done
test -f web_out/index.html
rm -rf www && mv web_out www

# 2) الحزم
npm install --no-audit --no-fund
npm install --no-audit --no-fund @capacitor/status-bar@^6.0.1
if [ "$MODE" = "full" ]; then
  npm install --no-audit --no-fund @capacitor/local-notifications@^6.0.0 @capacitor/geolocation@^6.0.0 @jofr/capacitor-media-session@^4.0.0
  # الخدمة الأمامية لتشغيل الصوت تحتاج نوعها على أندرويد 14
  PM=node_modules/@jofr/capacitor-media-session/android/src/main/AndroidManifest.xml
  if [ -f "$PM" ]; then
    echo "--- manifest الإضافة:"; cat "$PM"
    grep -q foregroundServiceType "$PM" || sed -i 's#<service #<service android:foregroundServiceType="mediaPlayback" #' "$PM"
  fi
fi

# 3) مشروع أندرويد
rm -rf android
npx cap add android
RES=android/app/src/main/res
M=android/app/src/main/AndroidManifest.xml

# إيقاف الأذان بزر الصوت وبسحب الستارة + تدوير الشاشة في المصحف
MA=MainActivity.java; [ -f native/MainActivity.java ] && MA=native/MainActivity.java
test -f "$MA"
F=$(find android/app/src/main/java -name MainActivity.java | head -n 1)
cp "$MA" "$F"

# الأذونات
perm() { grep -q "android.permission.$1\"" "$M" || sed -i "s#</manifest>#    <uses-permission android:name=\"android.permission.$1\" />\n</manifest>#" "$M"; }
perm ACCESS_FINE_LOCATION
perm ACCESS_COARSE_LOCATION
perm POST_NOTIFICATIONS
if [ "$MODE" = "full" ]; then
  perm USE_EXACT_ALARM
  perm RECEIVE_BOOT_COMPLETED
  perm WAKE_LOCK
  perm FOREGROUND_SERVICE
  perm FOREGROUND_SERVICE_MEDIA_PLAYBACK
fi
grep -c "uses-permission" "$M"

# الأيقونات
sudo apt-get update -y >/dev/null
sudo apt-get install -y imagemagick >/dev/null
CMD=$(command -v magick || command -v convert)
rm -rf $RES/mipmap-anydpi-v26
for d in mdpi:48 hdpi:72 xhdpi:96 xxhdpi:144 xxxhdpi:192; do
  n=${d%%:*}; s=${d##*:}
  $CMD www/icon-512.png -resize ${s}x${s} $RES/mipmap-$n/ic_launcher.png
  $CMD www/icon-512.png -resize ${s}x${s} $RES/mipmap-$n/ic_launcher_round.png
done
mkdir -p $RES/drawable
[ -f ic_stat_zad.xml ] && cp ic_stat_zad.xml $RES/drawable/ic_stat_zad.xml

# مفتاح توقيع ثابت: حتى يُثبَّت كل تحديث فوق السابق دون حذف التطبيق
mkdir -p "$HOME/.android"
[ -f zad-debug.keystore ] && cp zad-debug.keystore "$HOME/.android/debug.keystore"

npx cap sync android
( cd android && chmod +x gradlew && ./gradlew assembleDebug --no-daemon )

mkdir -p out
cp android/app/build/outputs/apk/debug/app-debug.apk "out/zad-$MODE.apk"
ls -l out
echo "==== انتهى بناء [$MODE] بنجاح ===="
