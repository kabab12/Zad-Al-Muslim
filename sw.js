/* زاد المسلم — Service Worker */
const V="zad-v3",CORE=["./","index.html","manifest.webmanifest","icon-192.png","icon-512.png"];
self.addEventListener("install",e=>{e.waitUntil(caches.open(V).then(c=>c.addAll(CORE).catch(()=>{})).then(()=>self.skipWaiting()))});
self.addEventListener("activate",e=>{e.waitUntil(caches.keys().then(k=>Promise.all(k.filter(x=>x!==V).map(x=>caches.delete(x)))).then(()=>self.clients.claim()))});
self.addEventListener("fetch",e=>{
 const r=e.request;if(r.method!=="GET")return;
 const u=new URL(r.url);
 if(u.origin!==location.origin||r.headers.has("range"))return; /* الصوت والمصادر الخارجية تمر مباشرة */
 if(r.mode==="navigate"){e.respondWith(fetch(r).then(x=>{const c=x.clone();caches.open(V).then(h=>h.put("index.html",c));return x}).catch(()=>caches.match("index.html")));return}
 e.respondWith(caches.match(r).then(h=>h||fetch(r).then(x=>{if(x.ok){const c=x.clone();caches.open(V).then(k=>k.put(r,c))}return x})))});
self.addEventListener("notificationclick",e=>{
 e.notification.close();const a=e.notification.data&&(e.notification.data.a||e.notification.data.nt);
 e.waitUntil(self.clients.matchAll({type:"window",includeUncontrolled:true}).then(l=>{
  if(l.length){const c=l[0];if(a)c.postMessage({nt:a});return c.focus()}
  return self.clients.openWindow("./")}))});
