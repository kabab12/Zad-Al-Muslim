#!/usr/bin/env bash
# شغّله من جذر مشروع Capacitor:  bash zad-native-patch/apply-patch.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
[ -f capacitor.config.json ] || [ -f capacitor.config.ts ] || { echo "شغّل السكربت من جذر مشروع Capacitor"; exit 1; }
npm install @capacitor/local-notifications@^6 @capacitor/geolocation@^6 --save
[ -d android ] || npx cap add android
mkdir -p android/app/src/main/java/com/zadalmuslim/app android/app/src/main/res/drawable
cp "$HERE/java/ZadMediaPlugin.java" "$HERE/java/ZadMediaService.java" android/app/src/main/java/com/zadalmuslim/app/
cp "$HERE/res/ic_stat_zad.xml" android/app/src/main/res/drawable/
python3 "$HERE/patch.py"
npx cap sync android
echo "تم. ابنِ الـAPK عبر GitHub Actions كالمعتاد."
