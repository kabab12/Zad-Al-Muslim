#!/usr/bin/env python3
"""
إصلاح زر تشغيل/إيقاف في إشعار الوسائط (إضافة @jofr/capacitor-media-session).

الخلل في الإضافة نفسها: تبني PendingIntent لزرّي Play/Pause بقيمتين مركّبتين
(ACTION_PLAY|ACTION_PLAY_PAUSE = 516 و ACTION_PAUSE|ACTION_PLAY_PAUSE = 514).
دالة MediaButtonReceiver.buildMediaButtonPendingIntent تقبل قيمة واحدة فقط (مقارنة تساوٍ)،
فترجع null ويصبح الزر الأوسط بلا أي أمر، بينما السابق/التالي/إيقاف (قيم مفردة) تعمل.
الحل: نجعل القيمة المركّبة قيمة مفردة (ACTION_PLAY أو ACTION_PAUSE) في استدعاء الـ PendingIntent فقط.
"""
import glob, re, sys

files = glob.glob('node_modules/@jofr/capacitor-media-session/android/src/main/java/**/MediaSessionService.java', recursive=True)
if not files:
    print('::warning::MediaSessionService.java not found - media button fix NOT applied')
    sys.exit(0)

CALL = re.compile(r'(\bbuildMediaButtonPendingIntent\(\s*[^,;]+?\s*,\s*)([^;]*?)(\s*\)\s*[,;)])', re.S)

def single(expr):
    e = re.sub(r'\s+', ' ', expr.strip())
    e = re.sub(r'(?:\w+\.)*ACTION_', 'ACTION_', e)
    if e == '516': return 'ACTION_PLAY'
    if e == '514': return 'ACTION_PAUSE'
    parts = [x.strip() for x in re.split(r'[|+]', e)]
    if len(parts) > 1 and 'ACTION_PLAY_PAUSE' in parts:
        rest = [x for x in parts if x != 'ACTION_PLAY_PAUSE']
        if len(rest) == 1 and rest[0] in ('ACTION_PLAY', 'ACTION_PAUSE'):
            return rest[0]
    return None

changed = 0
for p in files:
    t = open(p, encoding='utf-8').read()
    def rep(m):
        global changed
        s = single(m.group(2))
        if not s:
            return m.group(0)
        changed += 1
        return m.group(1) + 'android.support.v4.media.session.PlaybackStateCompat.' + s + m.group(3)
    t2 = CALL.sub(rep, t)
    if t2 != t:
        open(p, 'w', encoding='utf-8').write(t2)
        print('patched', p)
    print('--- buildMediaButtonPendingIntent calls now:')
    for i, line in enumerate(t2.splitlines(), 1):
        if 'buildMediaButtonPendingIntent' in line or 'ACTION_PLAY' in line or 'ACTION_PAUSE' in line:
            print('%4d: %s' % (i, line.strip()))
print('changed calls:', changed)
if not changed:
    print('::warning::media button pattern not found/changed - check the lines above')
