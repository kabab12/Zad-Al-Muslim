import os, re, json, shutil
here = os.path.dirname(os.path.abspath(__file__))
def rd(p): return open(p, encoding='utf-8').read()
def wr(p, s): open(p, 'w', encoding='utf-8').write(s)

# 1) AndroidManifest
mp = 'android/app/src/main/AndroidManifest.xml'
m = rd(mp)
perms = ['POST_NOTIFICATIONS', 'SCHEDULE_EXACT_ALARM', 'RECEIVE_BOOT_COMPLETED', 'FOREGROUND_SERVICE',
         'FOREGROUND_SERVICE_MEDIA_PLAYBACK', 'WAKE_LOCK', 'VIBRATE', 'ACCESS_FINE_LOCATION', 'ACCESS_COARSE_LOCATION']
add = ''.join('    <uses-permission android:name="android.permission.%s" />\n' % p
              for p in perms if 'android.permission.%s"' % p not in m)
if add: m = m.replace('</manifest>', add + '</manifest>')
if 'ZadMediaService' not in m:
    m = m.replace('</application>', '        <service android:name=".ZadMediaService" android:exported="false" android:foregroundServiceType="mediaPlayback" />\n    </application>')
wr(mp, m)

# 2) app/build.gradle
gp = 'android/app/build.gradle'
g = rd(gp)
if 'androidx.media:media' not in g:
    g = re.sub(r'dependencies\s*\{', 'dependencies {\n    implementation "androidx.media:media:1.7.0"', g, count=1)
    wr(gp, g)

# 3) MainActivity
mj = 'android/app/src/main/java/com/zadalmuslim/app/MainActivity.java'
cur = rd(mj) if os.path.exists(mj) else ''
if 'ZadMediaPlugin' not in cur:
    if 'onCreate' not in cur:
        shutil.copy(os.path.join(here, 'java/MainActivity.java'), mj)
    else:
        cur = re.sub(r'super\.onCreate\(', 'registerPlugin(ZadMediaPlugin.class);\n        super.onCreate(', cur, count=1)
        wr(mj, cur)
        print('تنبيه: MainActivity مخصّصة؛ سُجّلت الإضافة فقط. أضف يدويًا onPause من java/MainActivity.java إن أردت.')

# 4) capacitor.config.json
cp = 'capacitor.config.json'
if os.path.exists(cp):
    c = json.loads(rd(cp))
    c.setdefault('plugins', {})['LocalNotifications'] = {'smallIcon': 'ic_stat_zad', 'iconColor': '#0F6B4F'}
    wr(cp, json.dumps(c, ensure_ascii=False, indent=2))
else:
    print('تنبيه: capacitor.config.json غير موجود (ts؟). أضف LocalNotifications.smallIcon = ic_stat_zad يدويًا.')

# 5) حقن native-glue.js في أول <head>
done = 0
for p in ['www/index.html', 'index.html', 'public/index.html', 'src/index.html']:
    if not os.path.exists(p): continue
    h = rd(p)
    if 'native-glue.js' not in h:
        h = re.sub(r'(<head[^>]*>)', r'\1<script src="native-glue.js"></script>', h, count=1)
        wr(p, h)
    shutil.copy(os.path.join(here, 'native-glue.js'), os.path.join(os.path.dirname(p) or '.', 'native-glue.js'))
    done += 1
    print('حُقن في', p)
if not done: print('تنبيه: لم أجد index.html؛ أضف <script src="native-glue.js"></script> أول <head> وانسخ الملف بجانبه.')
