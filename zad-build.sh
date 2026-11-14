#!/usr/bin/env bash
# بناء APK زاد المسلم
# الاستخدام: bash zad-build.sh basic   أو   bash zad-build.sh full
#   full  = كل الميزات (إشعارات الستارة، التحكم بالصوت في الستارة، الموقع)
#   basic = نسخة بناء مضمونة بلا إضافات
set -euo pipefail

MODE="${1:-full}"
echo "==== زاد المسلم: بناء النسخة [$MODE] ===="

# ---------- 1) ملفات الويب ----------
SRC="."
if [ -f www/index.html ] && [ ! -f index.html ]; then SRC="www"; fi
rm -rf web_out && mkdir -p web_out

for f in index.html sw.js manifest.webmanifest styles.css override.css native.js data-icons.js app.js tafsir-data.js adhan-data.js; do
  if [ -f "$SRC/$f" ]; then cp "$SRC/$f" web_out/; else echo "تنبيه: الملف غير موجود: $f"; fi
done
for f in "$SRC"/icon-*.png; do
  if [ -f "$f" ]; then cp "$f" web_out/; fi
done

test -f web_out/index.html
rm -rf www && mv web_out www

# ---------- 2) الحزم ----------
npm install --no-audit --no-fund
npm install --no-audit --no-fund @capacitor/status-bar@^6.0.1

if [ "$MODE" = "full" ]; then
  npm install --no-audit --no-fund @capacitor/local-notifications@^6.0.0 @capacitor/geolocation@^6.0.0 @jofr/capacitor-media-session@^4.0.0

  # خدمة المقدمة ضرورية لتشغيل ال
