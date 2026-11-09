/* زاد المسلم — جسر أندرويد الأصلي: الموقع + الإشعارات المجدولة + إشعار الوسائط */
(function () {
  'use strict';
  var C = window.Capacitor;
  if (!C || !C.isNativePlatform || !C.isNativePlatform()) return;
  var P = C.Plugins || {};
  var LN = P.LocalNotifications, GEO = P.Geolocation, ZM = P.ZadMedia;

  /* ================= الموقع + تحديد المدينة تلقائيًا ================= */
  function granted(s) { return s && (s.location === 'granted' || s.coarseLocation === 'granted'); }
  function ensureLoc() {
    return GEO.checkPermissions().then(function (s) {
      if (granted(s)) return true;
      return GEO.requestPermissions({ permissions: ['location', 'coarseLocation'] }).then(granted);
    });
  }
  function mapErr(e) {
    var m = String((e && e.message) || e || '').toLowerCase();
    var c = /denied|permission/.test(m) ? 1 : /timeout|timed out/.test(m) ? 3 : 2;
    return { code: c, message: m, PERMISSION_DENIED: 1, POSITION_UNAVAILABLE: 2, TIMEOUT: 3 };
  }
  function toPos(p) { return { coords: p.coords, timestamp: p.timestamp }; }
  var watches = {}, cleared = {}, wid = 0;
  var geo = {
    getCurrentPosition: function (ok, err, o) {
      o = o || {};
      ensureLoc().then(function (g) {
        if (!g) throw new Error('permission denied');
        return GEO.getCurrentPosition({ enableHighAccuracy: !!o.enableHighAccuracy, timeout: o.timeout || 15000, maximumAge: o.maximumAge || 0 });
      }).then(function (p) { if (ok) ok(toPos(p)); setTimeout(nameCity, 80); })
        .catch(function (e) { if (err) err(mapErr(e)); });
    },
    watchPosition: function (ok, err, o) {
      o = o || {};
      var id = ++wid;
      ensureLoc().then(function (g) {
        if (!g) throw new Error('permission denied');
        return GEO.watchPosition({ enableHighAccuracy: !!o.enableHighAccuracy, timeout: o.timeout || 15000, maximumAge: o.maximumAge || 0 }, function (p, e) {
          if (e) { if (err) err(mapErr(e)); return; }
          if (p) { if (ok) ok(toPos(p)); setTimeout(nameCity, 80); }
        });
      }).then(function (h) {
        if (cleared[id]) { GEO.clearWatch({ id: h }); return; }
        watches[id] = h;
      }).catch(function (e) { if (err) err(mapErr(e)); });
      return id;
    },
    clearWatch: function (id) {
      cleared[id] = 1;
      if (watches[id] != null) { try { GEO.clearWatch({ id: watches[id] }); } catch (e) {} delete watches[id]; }
    }
  };
  try { Object.defineProperty(navigator, 'geolocation', { value: geo, configurable: true }); } catch (e) {}
  try {
    var oq = navigator.permissions && navigator.permissions.query ? navigator.permissions.query.bind(navigator.permissions) : null;
    if (navigator.permissions) navigator.permissions.query = function (d) {
      if (d && d.name === 'geolocation') {
        return GEO.checkPermissions().then(function (s) {
          return { state: granted(s) ? 'granted' : (s.location === 'denied' ? 'denied' : 'prompt') };
        });
      }
      return oq ? oq(d) : Promise.reject(new Error('unsupported'));
    };
  } catch (e) {}

  function hav(a, b, c, d) {
    var r = Math.PI / 180, x = Math.sin((c - a) * r / 2), y = Math.sin((d - b) * r / 2);
    var h = x * x + Math.cos(a * r) * Math.cos(c * r) * y * y;
    return 12742 * Math.asin(Math.sqrt(h));
  }
  function nameCity() { /* أقرب مدينة من القائمة (حتى 60 كم) مع بقاء الإحداثيات الدقيقة */
    try {
      if (typeof S === 'undefined' || typeof CITIES === 'undefined' || S.lat == null) return;
      var best = null, bd = 1e9;
      CITIES.forEach(function (c) { var d = hav(S.lat, S.lng, c[1], c[2]); if (d < bd) { bd = d; best = c; } });
      if (best && bd < 60) { S.city = best[0]; S.autoCity = 1; save(); }
    } catch (e) {}
  }
  function autoLoc() { /* أول تشغيل: حدّد الموقع والمدينة تلقائيًا */
    try {
      if (typeof S === 'undefined' || S.chosen || typeof reqLoc !== 'function') return;
      reqLoc(function (ok) {
        if (ok) { nameCity(); try { go(cur === 'home' || cur === 'qibla' || cur === 'set' ? cur : 'home'); } catch (e) {} }
      });
    } catch (e) {}
  }
  setTimeout(autoLoc, 2500);

  /* ================= الإشعارات (مجدولة أصليًا) ================= */
  var perm = 'default', sched = {}, DAYS = 7, CAP = 450, STORE = 'zadSchedV1';
  function mapPerm(s) { return s === 'granted' ? 'granted' : (s === 'denied' ? 'denied' : 'default'); }
  function refreshPerm() {
    return LN.checkPermissions().then(function (r) { perm = mapPerm(r.display); return perm; }).catch(function () { return perm; });
  }
  function Notif(title, o) {
    o = o || {};
    this.title = title; this.close = function () {};
    var nowMin = Math.floor(Date.now() / 60000);
    for (var k = -2; k <= 2; k++) if (sched[title + '|' + (nowMin + k)]) return; /* مجدول أصليًا: لا تكرار */
    if (perm !== 'granted') return;
    LN.schedule({ notifications: [{ id: 90000 + Math.floor(Math.random() * 9000), title: String(title), body: o.body || '', largeBody: o.body || '',
      schedule: { at: new Date(Date.now() + 600), allowWhileIdle: true }, channelId: 'zad_general', smallIcon: 'ic_stat_zad',
      extra: { a: (o.data && o.data.a) || 'nt' } }] }).catch(function () {});
  }
  Object.defineProperty(Notif, 'permission', { get: function () { return perm; } });
  Notif.requestPermission = function (cb) {
    return LN.requestPermissions().then(function (r) {
      perm = mapPerm(r.display);
      if (perm === 'granted') { ensureExact(); schedSoon(); }
      if (cb) cb(perm);
      return perm;
    }).catch(function () { return perm; });
  };
  window.Notification = Notif;

  function ensureExact() {
    try {
      LN.checkExactNotificationSetting().then(function (r) {
        if (r.exact_alarm === 'granted') return;
        if (localStorage.getItem('zadExactAsked')) return;
        localStorage.setItem('zadExactAsked', '1');
        if (confirm('لتصل التنبيهات في موعدها بالضبط، فعّل «المنبّهات والتذكيرات» لتطبيق زاد المسلم في الشاشة التالية ثم ارجع للتطبيق.')) LN.changeExactNotificationSetting();
      }).catch(function () {});
    } catch (e) {}
  }

  function pad(n) { return (n < 10 ? '0' : '') + n; }
  function atHM(day, hm) { return new Date(day.getFullYear(), day.getMonth(), day.getDate(), +hm.slice(0, 2), +hm.slice(3, 5)); }
  var off;
  function pick(tag, title, base) { /* رسالة (آية/حديث) دون تعديل عدّاد التطبيق */
    var cat = null, body = base || '', item = null;
    try { cat = ntCat(tag, title); } catch (e) {}
    if (cat && typeof NTP !== 'undefined' && NTP[cat]) {
      var Pk = NTP[cat], n = ((S.ntI && S.ntI[cat]) || 0) + (off[cat] = (off[cat] || 0) + 1);
      var it = Pk.i[(n - 1) % Pk.i.length], x = Pk.x ? Pk.x[(n - 1) % Pk.x.length] : null;
      body = (Pk.keep && base ? base + '\n' : '') + it[1] + '\n— ' + it[2] + (x ? '\n\n' + x[1] + '\n— ' + x[2] : '');
      item = { cat: cat, k: it[0], m: it[1], s: it[2], x: x ? { k: x[0], t: x[1], s: x[2] } : null, b: Pk.keep ? base : '', a: Pk.a };
    }
    return { body: body, item: item };
  }
  function buildList() {
    var now = Date.now(), out = []; off = {};
    function add(t, title, tag, base, ch, act, silentSound) {
      t = +t; if (!(t > now + 5000)) return;
      var m = pick(tag, title, base);
      out.push({ t: t, title: title, body: m.body, ch: ch, a: (m.item && m.item.a) || act || 'nt', item: m.item });
    }
    for (var i = 0; i < DAYS; i++) {
      var day = new Date(); day.setDate(day.getDate() + i); day.setHours(12, 0, 0, 0);
      var dow = day.getDay();
      NTD.forEach(function (d) {
        var c = ntS(d[0]); if (!c.on) return;
        var ds = { kahf: [5], mon: [0], thu: [3] }[d[0]]; if (ds && ds.indexOf(dow) < 0) return;
        add(atHM(day, c.t), d[1], d[0], ntMsg(d[4] == 'fast' ? 'fast' : d[4][0] == 'q' ? d[4] : 'adhkar'), 'zad_general', d[4]);
      });
      var sc = ntS('salat');
      if (sc.on) for (var m = ntm(sc.from); m <= ntm(sc.to); m += Math.max(1, sc.every)) add(atHM(day, pad(Math.floor(m / 60)) + ':' + pad(m % 60)), 'الصلاة على النبي ﷺ', 'salat', ntMsg('salat'), 'zad_general', 'nt');
      var ad = adS();
      if (ad.on) { var tm = times(day); ADN.forEach(function (p, k) { if (ad.pr[k] && tm[p[0]]) add(tm[p[0]], 'حان الآن وقت صلاة ' + p[1], 'd|' + p[0], 'حيّ على الصلاة', 'zad_prayer', 'nt'); }); }
      (S.ntc || []).forEach(function (x) {
        if (!x.on || x.done) return;
        var cand = x.rep == 'once' ? new Date(x.d + 'T' + x.t) : atHM(day, x.t);
        if (x.rep == 'once' && i > 0) return;
        if (x.rep == 'weekly' && cand.getDay() != new Date(x.d + 'T00:00').getDay()) return;
        add(+cand - (x.pre || 0) * 60000, x.n, 'c' + x.id, x.ds || ('حان موعد التنبيه' + (x.pre ? ' بعد ' + x.pre + ' دقيقة' : '')), 'zad_general', 'nt');
      });
    }
    out.sort(function (a, b) { return a.t - b.t; });
    return out.slice(0, CAP);
  }
  function reconcile() { /* ما وصل فعلًا: سجّله وقدّم عدّاد الرسائل */
    var prev = []; try { prev = JSON.parse(localStorage.getItem(STORE) || '[]'); } catch (e) {}
    var now = Date.now(), due = prev.filter(function (e) { return e.t <= now; });
    if (!due.length) return;
    S.ntI = S.ntI || {};
    due.forEach(function (e) { if (e.cat) S.ntI[e.cat] = (S.ntI[e.cat] || 0) + 1; });
    due.slice(-20).forEach(function (e) {
      if (!e.cat) return;
      try { ntLog({ id: e.t + '-' + e.cat, ts: e.t, t: e.title, m: e.m, s: e.s, k: e.k, x: e.x, b: e.b || '', a: e.a }); } catch (x) {}
    });
    try { save(); } catch (e) {}
  }
  var busy = false;
  function reschedule() {
    if (busy || typeof S === 'undefined' || typeof NTD === 'undefined') return Promise.resolve();
    busy = true;
    return refreshPerm().then(function () {
      if (perm !== 'granted') return;
      reconcile();
      return LN.getPending().then(function (p) {
        var ids = (p.notifications || []).map(function (n) { return { id: n.id }; });
        return ids.length ? LN.cancel({ notifications: ids }) : null;
      }).then(function () {
        var list = buildList(), keep = [];
        sched = {};
        var items = list.map(function (e, i) {
          sched[e.title + '|' + Math.floor(e.t / 60000)] = 1;
          keep.push({ t: e.t, title: e.title, cat: e.item && e.item.cat, k: e.item && e.item.k, m: e.item && e.item.m, s: e.item && e.item.s, x: e.item && e.item.x, b: e.item && e.item.b, a: e.a });
          return { id: 100000 + i, title: e.title, body: e.body, largeBody: e.body, schedule: { at: new Date(e.t), allowWhileIdle: true },
            channelId: e.ch, smallIcon: 'ic_stat_zad', extra: { a: e.a } };
        });
        try { localStorage.setItem(STORE, JSON.stringify(keep)); } catch (e) {}
        var p = Promise.resolve();
        for (var i = 0; i < items.length; i += 80) (function (ch) { p = p.then(function () { return LN.schedule({ notifications: ch }); }); })(items.slice(i, i + 80));
        return p;
      });
    }).catch(function () {}).then(function () { busy = false; });
  }
  var st = null;
  function schedSoon() { clearTimeout(st); st = setTimeout(reschedule, 1500); }
  window.zadReschedule = reschedule;

  var lastSig = '';
  function sig() {
    try {
      var tm = times(new Date());
      return JSON.stringify([S.nt, S.ntc, S.ad && [S.ad.on, S.ad.pr], ADN.map(function (p) { return +tm[p[0]]; })]);
    } catch (e) { return ''; }
  }
  setInterval(function () { var s = sig(); if (s && s !== lastSig) { lastSig = s; schedSoon(); } }, 6000);
  document.addEventListener('visibilitychange', function () { if (document.visibilityState === 'visible') schedSoon(); });

  try {
    LN.createChannel({ id: 'zad_general', name: 'تنبيهات زاد المسلم', description: 'الأذكار والآيات والتذكيرات', importance: 4, visibility: 1, vibration: true });
    LN.createChannel({ id: 'zad_prayer', name: 'أوقات الصلاة', description: 'تنبيه دخول وقت الصلاة', importance: 5, visibility: 1, vibration: true });
    LN.addListener('localNotificationActionPerformed', function (ev) {
      var a = ev && ev.notification && ev.notification.extra && ev.notification.extra.a;
      var run = function () { if (typeof ntOpen === 'function') ntOpen(a || 'nt'); };
      if (document.readyState === 'complete') setTimeout(run, 500); else window.addEventListener('load', function () { setTimeout(run, 900); });
    });
  } catch (e) {}
  refreshPerm().then(function () { if (perm === 'granted') { ensureExact(); schedSoon(); } });

  /* ================= إشعار الوسائط في الستارة ================= */
  if (!window.MediaMetadata) window.MediaMetadata = function (o) { for (var k in o) this[k] = o[k]; };
  var ms = { md: null, ps: 'none', h: {}, pos: null }, shown = false, pt = null;
  function push() {
    clearTimeout(pt);
    pt = setTimeout(function () {
      if (!ms.md || ms.ps === 'none') { if (shown) { shown = false; ZM.stop().catch(function () {}); } return; }
      shown = true;
      var p = ms.pos || {};
      ZM.update({ title: ms.md.title || '', artist: ms.md.artist || '', playing: ms.ps === 'playing', position: p.position || 0, duration: p.duration || 0,
        hasPrev: !!ms.h.previoustrack, hasNext: !!ms.h.nexttrack, hasSeek: !!ms.h.seekto }).catch(function () {});
    }, 120);
  }
  var shim = {
    get metadata() { return ms.md; }, set metadata(v) { ms.md = v; push(); },
    get playbackState() { return ms.ps; }, set playbackState(v) { ms.ps = v; push(); },
    setActionHandler: function (a, f) { if (f) ms.h[a] = f; else delete ms.h[a]; },
    setPositionState: function (p) { ms.pos = p; push(); }
  };
  try { Object.defineProperty(navigator, 'mediaSession', { value: shim, configurable: true }); } catch (e) {}
  try {
    ZM.addListener('mediaAction', function (e) {
      var k = { play: 'play', pause: 'pause', next: 'nexttrack', previous: 'previoustrack', stop: 'stop', seekto: 'seekto' }[e.action];
      var f = ms.h[k]; if (!f) return;
      try { k === 'seekto' ? f({ action: k, seekTime: e.position }) : f({ action: k }); } catch (x) {}
    });
  } catch (e) {}
})();
