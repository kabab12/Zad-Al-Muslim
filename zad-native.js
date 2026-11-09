
/* ======== أندرويد الأصلي: إشعار الوسائط في الستارة + جدولة التنبيهات (Capacitor LocalNotifications) ======== */
window.ZN={on:false,perm:"default",sig:"",t:0};
const zLN=()=>{try{return Capacitor.Plugins.LocalNotifications||null}catch(e){return null}};

/* ---- إشعار الوسائط: الصفحة تخبر الخدمة الأصلية، والخدمة تعيد أزرار الستارة إلى الصفحة ---- */
window.ZMH={};
function zMsMeta(title,artist,h){window.ZMH=h||{};const M=window.MediaNative;if(M)M.meta(String(title),String(artist||""),!!(h&&h.prev),!!(h&&h.next))}
function zMsState(a){const M=window.MediaNative;if(!M||!a)return;const d=isFinite(a.duration)&&a.duration>0?Math.round(a.duration*1000):0;M.state(!a.paused,Math.round((a.currentTime||0)*1000),d)}
window.zadMediaCmd=function(c,v){const h=window.ZMH||{};try{
 if(c=="play"&&h.play)h.play();else if(c=="pause"&&h.pause)h.pause();else if(c=="prev"&&h.prev)h.prev();else if(c=="next"&&h.next)h.next();
 else if(c=="stop"){(h.stop||h.pause||function(){})()}else if(c=="seek"&&h.st)h.st({seekTime:(+v||0)/1000})}catch(e){}};

/* ---- الأذونات ---- */
async function zPerm(ask){const LN=zLN();if(!LN)return ZN.perm;try{let r=await LN.checkPermissions(),p=r.display;if(ask&&p!=="granted"){r=await LN.requestPermissions();p=r.display}ZN.perm=p==="granted"?"granted":p==="denied"?"denied":"default"}catch(e){}return ZN.perm}
function zReq(){if(window.ZN&&ZN.on)return zPerm(true).then(()=>{ZN.sig="";zPlanSoon(true)});return Notification.requestPermission()}
async function zExact(ask){try{const LN=zLN();let r=await LN.checkExactNotificationSetting();if(r.exact_alarm!=="granted"&&ask)r=await LN.changeExactNotificationSetting();return r.exact_alarm==="granted"}catch(e){return true}}

/* ---- بناء نص التنبيه دون آثار جانبية (مثل ntFire لكن بلا تسجيل ولا صوت) ---- */
function zCompose(t,b,tag,act){const m=ntPick(tag,t);let body=b,a=act,lg=null;
 if(m){a=m.a||act;body=(m.keep&&b?b+"\n":"")+m.t+"\n— "+m.s+(m.x?"\n\n"+m.x.t+"\n— "+m.x.s:"");lg={t,m:m.t,s:m.s,k:m.k,x:m.x,b:m.keep?b:"",a}}
 return{t,body:String(body||""),a:a||"nt",lg}}

/* ---- خطة التنبيهات القادمة (٣٨٠ تنبيهًا كحدٍّ أقصى، الأقرب أولًا) ---- */
function zAt(d,hm){return new Date(d.getFullYear(),d.getMonth(),d.getDate(),+hm.slice(0,2),+hm.slice(3,5),0,0)}
function zPlan(){
 const now=Date.now(),out=[],base=new Date();base.setHours(0,0,0,0);
 const add=(when,ch,t,b,tag,act)=>{when=+when;if(!(when>now+5000))return;let c;try{c=zCompose(t,b,tag,act)}catch(e){c={t,body:String(b||""),a:act,lg:null}}out.push({at:when,ch,t:c.t,body:c.body,a:c.a,lg:c.lg})};
 const ad=adS(),nx=nxCfg(),k=S.kh;let nxP=nx.d.p||0,nxUsed=0;const NXL=nx.d.on?nxFlat(nx):[];
 (S.ntc||[]).forEach(x=>{if(!x.on||x.done||x.rep!="once")return;try{add(new Date(x.d+"T"+x.t)-x.pre*6e4,x.sl?"zad_quiet":"zad_adhkar",x.n,x.ds||"حان موعد التنبيه"+(x.pre?" بعد "+x.pre+" دقيقة":""),x.id,"nt")}catch(e){}});
 for(let i=0;i<14;i++){
  const d=new Date(base.getFullYear(),base.getMonth(),base.getDate()+i),dy=d.getDay();let T=null;
  try{T=times(d)}catch(e){}
  NTD.forEach(x=>{const c=ntS(x[0]);if(!c.on)return;const ds={kahf:[5],mon:[0],thu:[3]}[x[0]];if(ds&&!ds.includes(dy))return;
   add(zAt(d,c.t),"zad_adhkar",x[1],ntMsg(x[4]=="fast"?"fast":x[4][0]=="q"?x[4]:"adhkar"),x[0],x[4])});
  const sc=ntS("salat");if(sc.on&&i<3){const f=ntm(sc.from),to=ntm(sc.to);for(let m=f;m<=to;m+=sc.every)add(new Date(d.getFullYear(),d.getMonth(),d.getDate(),0,m),"zad_adhkar","الصلاة على النبي ﷺ",ntMsg("salat"),"salat","nt")}
  (S.ntc||[]).forEach(x=>{if(!x.on||x.done||x.rep=="once")return;try{if(x.rep=="weekly"&&new Date(x.d+"T00:00").getDay()!=dy)return;
   add(zAt(d,x.t)-x.pre*6e4,x.sl?"zad_quiet":"zad_adhkar",x.n,x.ds||"حان موعد التنبيه"+(x.pre?" بعد "+x.pre+" دقيقة":""),x.id,"nt")}catch(e){}});
  if(ad.on&&T)ADN.forEach((p,j)=>{if(ad.pr[j])add(T[p[0]],"zad_prayer","حان الآن وقت صلاة "+p[1],"حيّ على الصلاة",d.toDateString()+"|"+p[0],"nt")});
  if(nx.q.on)add(zAt(d,nx.q.t),"zad_adhkar","🌙 قيام الليل",ntMsg("qiyam"),"qiyam","qr");
  if(nx.h.on&&dy==5&&T)add(T[4].getTime()-nx.h.off*6e4,"zad_adhkar","🕌 ساعة الإجابة",ntMsg("jumua"),"jumua","c:"+NXC);
  if(NXL.length&&i<3){const f=ntm(nx.d.from),to=ntm(nx.d.to);for(let m=f;m<=to;m+=nx.d.every){
    const[kk,ii]=NXL[(nxP+nxUsed)%NXL.length];nxUsed++;const x=HD[kk][ii],txt=String(x[0]).replace(/<[^>]*>/g,"").trim(),
    body=(txt.length>230?txt.slice(0,230).replace(/\s+\S*$/,"")+"…":txt)+(x[1]>1?"\n(تُقال "+AD(x[1])+" مرات)":"")+"\n— اضغط لتبدأ الذكر مع المسبحة";
    const when=new Date(d.getFullYear(),d.getMonth(),d.getDate(),0,m);
    if(+when>now+5000)out.push({at:+when,ch:"zad_adhkar",t:"📿 "+CHN(kk),body,a:"d:"+kk+":"+ii,lg:{t:"📿 "+CHN(kk),m:txt,s:String(x[2]||"").replace(/<[^>]*>/g,""),k:"h",x:null,b:"",a:"d:"+kk+":"+ii}})}}
  if(k&&k.on&&k.pos<=604){let p=null;try{p=khPlan()}catch(e){}[["1",k.t1,k.r1],["2",k.t2,k.r2]].forEach(([n,t,r])=>{if(!r||!t)return;
   if(i==0&&p&&p.done)return;
   const b=i==0&&p&&!p.fin?"وردك اليوم: ص "+AD(p.s)+" – "+AD(p.e)+" · بقي "+AD(p.rest)+" صفحة":"حان موعد وردك اليوم من القرآن · اضغط لفتح الورد";
   add(zAt(d,t),"zad_adhkar","📖 ورد اليوم من القرآن",b,"khatma","kh")})}
 }
 if(NXL.length)nx.d.p=(nxP+nxUsed)%NXL.length;
 out.sort((a,b)=>a.at-b.at);return out.slice(0,380)}

/* ---- الجدولة الفعلية (تُلغي القديم وتُنشئ الجديد) ---- */
let zBusy=0,zAgain=0,zTm=null;
async function zApply(){
 if(!ZN.on)return;if(zBusy){zAgain=1;return}zBusy=1;
 try{const LN=zLN();if(ZN.perm!=="granted")await zPerm(false);
  let pend=[];try{pend=(await LN.getPending()).notifications||[]}catch(e){}
  const ids=pend.filter(n=>n.id<1e5).map(n=>({id:n.id}));if(ids.length)await LN.cancel({notifications:ids});
  if(ZN.perm==="granted"){
   const plan=zPlan(),list=plan.map((x,i)=>({id:i+1,title:x.t,body:x.body.length>150?x.body.slice(0,147)+"…":x.body,largeBody:x.body,channelId:x.ch,smallIcon:"ic_stat_zad",
     schedule:{at:new Date(x.at).toISOString(),allowWhileIdle:true},extra:{z:1,a:x.a,ts:x.at,lg:x.lg}}));
   for(let i=0;i<list.length;i+=40)await LN.schedule({notifications:list.slice(i,i+40)});ZN.n=list.length}
  save()}catch(e){}
 zBusy=0;if(zAgain){zAgain=0;zApply()}}
function zPlanSoon(force){if(!ZN.on)return;clearTimeout(zTm);zTm=setTimeout(zApply,force?300:2500)}
function zSig(){try{const k=S.kh||{},n=S.nx||{},a=S.ad||{};return JSON.stringify([S.nt,S.ntc,n.q,n.h,n.d&&[n.d.on,n.d.from,n.d.to,n.d.every,n.d.all],a.on,a.pr,S.lat,S.lng,S.asr,S.adj,S.tz,k.on,k.t1,k.t2,k.r1,k.r2,k.ds,k.pos,ZN.perm,new Date().toDateString()])}catch(e){return Math.random()+""}}
function zSaved(){if(!ZN.on)return;const s=zSig();if(s!==ZN.sig){ZN.sig=s;zPlanSoon()}}

/* ---- تنبيه فوري (التجارب، والمواسم، وما لا يُجدوَل مسبقًا) ---- */
async function zNow(t,b,tag,act,silent){
 const LN=zLN(),c=zCompose(t,b,tag,act);if(c.lg)ntLog({id:Date.now()+"-"+Math.random().toString(36).slice(2,6),ts:Date.now(),...c.lg});
 if(ZN.perm!=="granted"){await zPerm(false);if(ZN.perm!=="granted"){ntBanner(c.t,c.body,c.a);return}}
 try{await LN.schedule({notifications:[{id:1e5+Math.floor(Math.random()*9e5),title:c.t,body:c.body.length>150?c.body.slice(0,147)+"…":c.body,largeBody:c.body,
  channelId:silent?"zad_quiet":"zad_adhkar",smallIcon:"ic_stat_zad",schedule:{at:new Date(Date.now()+700).toISOString(),allowWhileIdle:true},extra:{z:2,a:c.a}}]})}catch(e){ntBanner(c.t,c.body,c.a)}}

/* ---- سجلّ الرسائل: ما وصل من التنبيهات المجدولة يُحفظ في «الرسائل المحفوظة» ---- */
function zLog(e){try{if(!e||!e.lg||!e.ts)return;const k=e.ts+":"+e.a;S.zl=S.zl||[];if(S.zl.includes(k))return;S.zl.push(k);if(S.zl.length>400)S.zl.shift();
 S.nlog=S.nlog||[];S.nlog.unshift({id:k,ts:e.ts,...e.lg});if(S.nlog.length>150)S.nlog.length=150;save()}catch(x){}}
async function zSync(){try{const r=await zLN().getDeliveredNotifications();(r.notifications||[]).forEach(n=>zLog(n.extra||n.data))}catch(e){}}

/* ---- واجهة الإعدادات (صفحة «التنبيهات والعبادات») ---- */
function zNoteHtml(){
 if(!ZN.on)return `<div class="mu" style="margin-top:6px">تعمل التنبيهات دون إنترنت ما دام التطبيق مفتوحًا أو في الخلفية. وصولها بعد إغلاقه تمامًا يحتاج تثبيته كتطبيق APK بجدولة إشعارات محلية.</div>`;
 return `<div class="mu" style="margin-top:6px">✓ التنبيهات تُجدوَل داخل النظام نفسه فتصلك دون إنترنت وبعد إغلاق التطبيق وإعادة تشغيل الهاتف (الأذكار، الصلاة على النبي، التنبيهات الخاصة، الصلوات، القيام، ساعة الإجابة، أذكار اليوم والليلة، ورد الختمة).${ZN.n!=null?" المجدول الآن: "+AD(ZN.n)+" تنبيهًا.":""} افتح التطبيق مرة كل أسبوع ليتجدد الجدول، ولا تُغلقه بـ«إيقاف إجباري».</div>
 <div class="set" style="gap:6px;flex-wrap:wrap"><button onclick="zExactBtn()">السماح بالتنبيهات الدقيقة</button><button onclick="try{SysNative.openBattery()}catch(e){}">إعدادات البطارية</button><button onclick="try{SysNative.openAppSettings()}catch(e){}">إعدادات التطبيق</button><button onclick="zTest1()">تجربة بعد دقيقة</button></div>
 <div class="mu" style="font-size:.85em">لضمان الوصول: اجعل بطارية «زاد المسلم» على «غير مقيَّدة» أو «بدون قيود»، وفي أجهزة شاومي وهواوي وأوبو وسامسونج فعّل «التشغيل التلقائي/الخلفية» للتطبيق.</div>`}
async function zExactBtn(){const ok=await zExact(true);qtoast(ok?"التنبيهات الدقيقة مسموحة ✓":"لم يُسمح بعدُ؛ فعّل «المنبهات والتذكيرات» للتطبيق")}
async function zTest1(){const LN=zLN();await zPerm(true);if(ZN.perm!=="granted"){qtoast("اسمح بالإشعارات أولًا");return}
 try{await LN.schedule({notifications:[{id:1e5+7,title:"تجربة زاد المسلم",body:"وصل التنبيه المجدول ✓ يمكنك الآن إغلاق التطبيق تمامًا وتجربة الوصول.",channelId:"zad_adhkar",smallIcon:"ic_stat_zad",schedule:{at:new Date(Date.now()+60000).toISOString(),allowWhileIdle:true},extra:{z:2,a:"nt"}}]});qtoast("سيصلك تنبيه بعد دقيقة. أغلق التطبيق وانتظر.")}catch(e){qtoast("تعذّرت الجدولة")}}

/* ---- التشغيل ---- */
async function zInit(){
 try{if(!fsNative())return;const LN=zLN();if(!LN)return;ZN.on=true;
  const ch=(id,name,imp,desc)=>LN.createChannel({id,name,description:desc,importance:imp,visibility:1,vibration:imp>2}).catch(()=>{});
  await ch("zad_adhkar","الأذكار والتذكيرات",4,"أذكار الصباح والمساء والورد والتذكيرات");
  await ch("zad_prayer","أوقات الصلاة",5,"تنبيه دخول وقت الصلاة");
  await ch("zad_quiet","تنبيهات صامتة",2,"تنبيهات بلا صوت");
  LN.addListener("localNotificationActionPerformed",e=>{const x=e&&e.notification&&e.notification.extra;zLog(x);setTimeout(()=>{try{ntOpen(x&&x.a||"nt")}catch(err){}},700)});
  await zPerm(true);ZN.sig=zSig();zPlanSoon(true);setTimeout(zSync,1500);
  document.addEventListener("visibilitychange",()=>{if(document.visibilityState=="visible"){zPerm(false).then(()=>{zSync();zPlanSoon(true)})}});
 }catch(e){}}
setTimeout(zInit,1200);
