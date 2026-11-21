/* زاد المسلم — طبقة التطبيق الأصلي (أندرويد)
   تعمل فقط داخل تطبيق APK (العنوان localhost + وجود Capacitor)، وتبقى خاملة في المتصفح.
   كل جزء محاط بحماية: إن غابت الإضافة الأصلية يعمل التطبيق كما كان. */
(function(){
  "use strict";
  if(location.hostname!=="localhost")return;
  var done=false;

  function boot(){
    if(done)return;
    var C=window.Capacitor;
    if(!C||!(C.isNativePlatform?C.isNativePlatform():true))return;
    done=true;

    function plug(n){
      try{
        var ok=C.isPluginAvailable?C.isPluginAvailable(n):false;
        if(!ok&&C.PluginHeaders){ok=C.PluginHeaders.some(function(h){return h&&h.name===n})}
        if(!ok)return null;
        return C.registerPlugin?C.registerPlugin(n):(C.Plugins&&C.Plugins[n])||null
      }catch(e){return null}
    }
    function safe(f){try{return f()}catch(e){}}

    /* ===== 1) التخزين الدائم: بيانات التطبيق محفوظة أصلًا داخل الـ APK ===== */
    safe(function(){if(navigator.storage){navigator.storage.persist=function(){return Promise.resolve(true)};navigator.storage.persisted=function(){return Promise.resolve(true)}}});

    /* ===== 2) الموقع عبر إضافة Capacitor الرسمية ===== */
    safe(function(){
      var G=function(){return plug("Geolocation")};
      if(!G())return;
      var orig=navigator.geolocation,lastToast=0,wid=0,watches={};
      var mkErr=function(c,m){return{code:c,message:m||"",PERMISSION_DENIED:1,POSITION_UNAVAILABLE:2,TIMEOUT:3}};
      var toErr=function(e){
        var m=String(e&&(e.message||e)||""),l=m.toLowerCase();
        if(/denied|not granted|permission/.test(l)&&!/services/.test(l))return mkErr(1,m);
        if(/timeout|timed out/.test(l))return mkErr(3,m);
        return mkErr(2,m)};
      var say=function(x){
        try{
          if(Date.now()-lastToast<8000||!window.qtoast)return;lastToast=Date.now();
          var t=x.code===1?"إذن الموقع مرفوض: فعّله من إعدادات الهاتف ← التطبيقات ← زاد المسلم ← الأذونات"
            :x.code===3?"انتهت مهلة تحديد الموقع، جرّب في مكان مكشوف"
            :/services/i.test(x.message)?"خدمة الموقع (GPS) مغلقة: فعّلها من إعدادات الهاتف ثم أعد المحاولة"
            :"تعذّر تحديد الموقع"+(x.message?": "+x.message:"");
          window.qtoast(t,5000)}catch(e){}};
      var granted=function(s){return !!s&&(s.location==="granted"||s.coarseLocation==="granted")};
      var ensure=function(g){
        return g.checkPermissions().then(function(s){
          if(granted(s))return 1;
          return g.requestPermissions().then(function(r){return granted(r)?1:0})
        }).catch(function(){return 0})};
      var cur=function(ok,err,opt){
        var g=G();
        if(!g){if(orig)return orig.getCurrentPosition(ok,err,opt);if(err)err(mkErr(2,"unsupported"));return}
        opt=opt||{};
        ensure(g).then(function(){
          return g.getCurrentPosition({enableHighAccuracy:!!opt.enableHighAccuracy,timeout:opt.timeout||20000,maximumAge:opt.maximumAge||0})
        }).then(function(p){if(ok)ok(p)},function(e){var x=toErr(e);say(x);if(err)err(x)})};
      var sh={
        getCurrentPosition:cur,
        watchPosition:function(ok,err,opt){
          var id=++wid,stop=false,timer=null;
          watches[id]=function(){stop=true;clearTimeout(timer)};
          (function tick(){
            if(stop)return;
            cur(function(p){if(stop)return;try{if(ok)ok(p)}catch(e){}timer=setTimeout(tick,5000)},
                function(e){if(stop)return;try{if(err)err(e)}catch(x){}timer=setTimeout(tick,5000)},opt)
          })();
          return id},
        clearWatch:function(id){var f=watches[id];if(f){f();delete watches[id]}}
      };
      try{Object.defineProperty(navigator,"geolocation",{value:sh,configurable:true})}
      catch(e){try{if(orig){orig.getCurrentPosition=sh.getCurrentPosition;orig.watchPosition=sh.watchPosition;orig.clearWatch=sh.clearWatch}}catch(x){}}
      var P=navigator.permissions,pq=P&&P.query?P.query.bind(P):null;
      if(P)P.query=function(d){
        if(d&&d.name==="geolocation"&&G()){
          return G().checkPermissions().then(function(s){
            return{state:granted(s)?"granted":(s&&s.location==="denied"?"denied":"prompt"),onchange:null}
          },function(){return pq?pq(d):Promise.reject(new TypeError("unsupported"))})}
        return pq?pq(d):Promise.reject(new TypeError("unsupported"))}
    });

    var NS=window.__zadNative=window.__zadNative||{};
    NS.status=function(){return Promise.resolve({native:true,ln:false,ms:!!plug("MediaSession"),geo:!!plug("Geolocation"),perm:"default",pending:0,next:""})};

    /* ===== 3) الإشعارات في ستارة الهاتف (تعمل والتطبيق مغلق) ===== */
    /* زر الرجوع في الهاتف: يرجع داخل التطبيق، وعلى الرئيسية يطلب ضغطتين للخروج */
    try{var APPP=plug("App"),lastBk=0;
      if(APPP&&APPP.addListener)APPP.addListener("backButton",function(ev){
        try{
          if(ev&&ev.canGoBack){history.back();return}
          if(typeof uiBack==="function"&&(cur!=="menu"||inQ||inR||SUBV||qst.length)){uiBack();return}
        }catch(e){}
        var t=Date.now();
        if(t-lastBk<2000){try{APPP.exitApp()}catch(e){}return}
        lastBk=t;try{qtoast("اضغط رجوع مرة أخرى للخروج")}catch(e){}
      })}catch(e){}
    var LN=plug("LocalNotifications");
    if(LN)safe(function(){
      var perm="default",SCH={},busy=false,again=false,SIM=false,chanP=null,timer=null;
      var mapPerm=function(s){var d=s&&(s.display||s.receive);return d==="granted"?"granted":(d==="denied"?"denied":"default")};
      var refreshPerm=function(){return LN.checkPermissions().then(function(s){perm=mapPerm(s);return perm},function(){return perm})};
      var ask=function(){
        return LN.requestPermissions().then(function(s){
          perm=mapPerm(s);if(perm==="granted")setTimeout(schedule,600);return perm
        },function(){return perm})};
      var hash=function(str){var h=2166136261;for(var i=0;i<str.length;i++){h^=str.charCodeAt(i);h=Math.imul(h,16777619)}return (h>>>0)%2000000000+1000};
      var zid=function(tag,min){return hash(String(tag)+"|"+min)};
      /* صوت الأذان كصوت قناة إشعار: يعمل والتطبيق مغلق ولا يعتمد على تشغيل الصفحة */
      var adhanChannels=function(){
        try{
          if(typeof ADEMB==="undefined"||!LN.createChannel)return Promise.resolve();
          var nm={};try{ADM.forEach(function(m){nm[m[0]]=m[1]})}catch(e){}
          return Promise.all(Object.keys(ADEMB).map(function(id){
            return LN.createChannel({id:"zad_adhan_"+id,name:"الأذان — "+(nm[id]||id),description:"صوت الأذان وقت الصلاة",importance:5,visibility:1,vibration:true,sound:"adhan_"+id+".ogg"}).catch(function(){})
          })).then(function(){
            if(!LN.listChannels)return;
            return LN.listChannels().then(function(r){window.__adNat=((r&&r.channels)||[]).some(function(c){return /^zad_adhan_/.test(c.id)})}).catch(function(){})
          })
        }catch(e){return Promise.resolve()}};
      var adCh=function(tag){
        try{
          if(!window.__adNat||!/\|/.test(String(tag||"")))return null;
          var c=S.ad;if(!c||!c.on||S.ntSnd===false)return null;
          return (typeof ADEMB!=="undefined"&&ADEMB[c.m])?"zad_adhan_"+c.m:null
        }catch(e){return null}};
      /* أصوات خاصة (عصافير الأذكار، وتلاوة «إن الله وملائكته يصلون على النبي») كأصوات قنوات إشعار */
      var soundChannels=function(){
        try{
          if(!LN.createChannel)return Promise.resolve();
          var defs=[["bird","الأذكار والختمة (عصافير)"],["ayah","الصلاة على النبي ﷺ (تلاوة)"]];
          return Promise.all(defs.map(function(d){
            return LN.createChannel({id:"zad_snd_"+d[0],name:d[1],description:"صوت خاص للتنبيه",importance:4,visibility:1,vibration:true,sound:"snd_"+d[0]+".mp3"}).catch(function(){})
          })).then(function(){
            if(!LN.listChannels)return;
            return LN.listChannels().then(function(r){var ids=((r&&r.channels)||[]).map(function(c){return c.id});window.__sdNat=ids.indexOf("zad_snd_bird")>=0&&ids.indexOf("zad_snd_ayah")>=0}).catch(function(){})
          })
        }catch(e){return Promise.resolve()}};
      var sdCh=function(tag){
        try{
          if(!window.__sdNat||typeof S==="undefined"||S.ntSnd===false||S.ntCs===0)return null;
          var c=ntCat(String(tag||""),"");if(!c||c==="adhan"||c==="custom")return null;
          var s=NTP[c]&&NTP[c].snd;return s?("zad_snd_"+s):null
        }catch(e){return null}};
      window.__sdCh=sdCh;
      var adTried=false;
      var ensureChannel=function(){
        if(!chanP)chanP=Promise.all([LN.createChannel({id:"zad_reminders",name:"تذكيرات زاد المسلم",description:"الأذكار والصلاة على النبي والأذان والورد",importance:4,visibility:1,vibration:true,lights:true}).catch(function(){}),LN.createChannel({id:"zad_silent",name:"تذكيرات زاد المسلم (صامتة)",description:"التذكيرات بلا صوت ولا اهتزاز",importance:2,visibility:1,vibration:false,lights:false}).catch(function(){})]).then(adhanChannels).then(soundChannels);
        return chanP};
      var clip=function(s,n){s=String(s||"");return s.length>n?s.slice(0,n-1).replace(/\s+\S*$/,"")+"…":s};
      var build=function(title,body,act,tag,atMs,id){
        return{id:id,title:String(title||"زاد المسلم"),body:clip(body,600),largeBody:String(body||""),summaryText:"زاد المسلم",
          schedule:{at:new Date(atMs),allowWhileIdle:true},channelId:adCh(tag)||sdCh(tag)||((typeof S!=="undefined"&&S.ntSnd===false)?"zad_silent":"zad_reminders"),smallIcon:"ic_stat_zad",extra:{a:act||"nt",tag:String(tag||"")}}};
      /* سجلّ ما أُرسل فعلًا: لا يتكرر التنبيه نفسه خلال دقيقة ونصف، حتى لو أُعيد فتح التطبيق */
      var ledger=function(){try{var o=JSON.parse(localStorage.getItem("zadFired")||"{}"),t=Date.now();Object.keys(o).forEach(function(k){if(t-o[k]>864e5)delete o[k]});return o}catch(e){return{}}};
      var fireNow=function(title,opts){
        opts=opts||{};
        var tag=opts.tag||"",now=Date.now(),m=Math.floor(now/6e4);
        if(tag){
          /* مجدول أصليًا (النظام سيُرسله أو أرسله): لا نرسل نسخة ثانية */
          if(SCH[zid(tag,m-1)]||SCH[zid(tag,m)]||SCH[zid(tag,m+1)])return;
          var L=ledger();
          if(L[tag]&&now-L[tag]<90000)return;
          L[tag]=now;try{localStorage.setItem("zadFired",JSON.stringify(L))}catch(e){}
        }
        var act=opts.data&&opts.data.a;
        ensureChannel().then(function(){
          return LN.schedule({notifications:[build(title,opts.body,act,tag,Date.now()+700,hash(tag+"|"+Date.now()+Math.random()))]})
        }).catch(function(){})};

      /* — محاكاة منطق التنبيهات الموجود في التطبيق على ساعة افتراضية لمعرفة ما سيُرسَل قريبًا — */
      var simulate=function(days,step){
        var out=[],RD=Date,ntF=window.ntFire,adP=window.adPlay,setItem=Storage.prototype.setItem,snap,start=RD.now(),fake=start;
        SIM=true;
        try{
          snap=JSON.stringify(S);
          var FD=class extends RD{constructor(...a){if(a.length===0){super(fake)}else{super(...a)}}static now(){return fake}};
          window.Date=FD;
          Storage.prototype.setItem=function(k){if(k==="zad")return;return setItem.apply(this,arguments)};
          window.adPlay=function(){};
          window.ntFire=function(t,b,tag,act){
            var m=null,body=b,a=act;
            try{m=ntPick(tag,t)}catch(e){}
            if(m){a=m.a||act;body=(m.keep&&b?b+"\n":"")+m.t+"\n— "+m.s+(m.x?"\n\n"+m.x.t+"\n— "+m.x.s:"")}
            out.push({at:fake,title:t,body:body,act:a,tag:String(tag||"")})};
          var end=start+days*864e5;
          for(fake=start;fake<end;fake+=step*1000){
            try{ntTick()}catch(e){}
            try{adTick()}catch(e){}
          }
        }finally{
          window.Date=RD;window.ntFire=ntF;window.adPlay=adP;Storage.prototype.setItem=setItem;
          try{var o=JSON.parse(snap);Object.keys(S).forEach(function(k){delete S[k]});Object.keys(o).forEach(function(k){S[k]=o[k]})}catch(e){}
          SIM=false}
        return out};

      /* توقيع إعدادات التنبيهات: لا نعيد الجدولة إلا إذا تغيّر شيء مؤثّر (نتجاهل ما يتغير باستمرار كموضع التلاوة) */
      var VOL={wd:1,wdH:1,qres:1,ql:1,qp:1,qn:1,ntl:1,adl:1,nlog:1,ntI:1,silOn:1,silWarn:1,lastOpen:1};
      var sigNow=function(){try{return "nx4|"+JSON.stringify(S,function(k,v){return VOL[k]?undefined:v})}catch(e){return String(Date.now())}};
      var lastSig=(function(){try{return localStorage.getItem("zadSchedSig")||""}catch(e){return""}})(),lastAt=0;
      var schedule=function(force){
        if(busy){again=true;return}
        if(perm!=="granted"||typeof S==="undefined"||typeof ntTick!=="function")return;
        var sg=sigNow(),t0=Date.now(),full=(sg!==lastSig);
        if(force===true)full=true;
        else if(!full&&t0-lastAt<3*36e5)return;
        if(!adTried){adTried=true;ensureChannel().then(function(){schedule(force)});return}
        busy=true;
        var plan=[];try{plan=simulate(3,15)}catch(e){plan=[]}
        var now=Date.now(),seen={},items=[];
        plan.filter(function(x){return x.at>now+4000}).sort(function(a,b){return a.at-b.at}).forEach(function(x){
          var m=Math.floor(x.at/6e4),id=zid(x.tag,m);
          if(seen[id]||items.length>=240)return;
          seen[id]=1;items.push(build(x.title,x.body,x.act,x.tag,x.at,id))});
        ensureChannel().then(function(){return LN.getPending()}).then(function(r){
          var p=(r&&r.notifications)||[],have={};
          p.forEach(function(n){have[n.id]=1});
          /* تغيّرت الإعدادات: نبني من جديد. وإلا فنُكمل الناقص ونحذف الزائد فقط (بلا إلغاء شامل) */
          var drop=p.filter(function(n){return full||!seen[n.id]});
          var add=items.filter(function(x){return full||!have[x.id]});
          var ch=Promise.resolve();
          if(drop.length)ch=ch.then(function(){return LN.cancel({notifications:drop.map(function(n){return{id:n.id}})})});
          for(var i=0;i<add.length;i+=40)(function(part){ch=ch.then(function(){return LN.schedule({notifications:part})})})(add.slice(i,i+40));
          return ch
        }).then(function(){SCH=seen;lastSig=sg;lastAt=Date.now();try{localStorage.setItem("zadSchedSig",sg)}catch(e){}}).catch(function(){}).then(function(){busy=false;if(again){again=false;setTimeout(function(){schedule()},2500)}})};

      window.zadResched=function(){try{schedule(true)}catch(e){}};
        /* واجهة Notification القياسية التي يستعملها التطبيق: تتحول إلى إشعار أصلي */
      var ZN=function(title,opts){this.title=title;this.body=(opts&&opts.body)||"";this.tag=(opts&&opts.tag)||"";this.onclick=null;this.close=function(){};fireNow(title,opts)};
      Object.defineProperty(ZN,"permission",{get:function(){return perm}});
      ZN.requestPermission=function(cb){return ask().then(function(p){if(cb)try{cb(p)}catch(e){}return p})};
      ZN.maxActions=0;
      window.Notification=ZN;
      safe(function(){if(window.ServiceWorkerRegistration)ServiceWorkerRegistration.prototype.showNotification=function(t,o){fireNow(t,o||{});return Promise.resolve()}});

      /* عند ظهور التنبيه داخل التطبيق نزيل نسخة الستارة حتى لا يتكرر */
      var wrapFire=function(){
        var f=window.ntFire;if(typeof f!=="function"||f.__zad)return;
        var w=function(t,b,tag){
          try{
            if(!SIM&&document.visibilityState==="visible"&&tag&&!adCh(tag)&&!sdCh(tag)){
              var m=Math.floor(Date.now()/6e4),ids=[zid(tag,m),zid(tag,m-1)].map(function(i){return{id:i}});
              LN.cancel({notifications:ids}).catch(function(){});
              if(LN.removeDeliveredNotifications)LN.removeDeliveredNotifications({notifications:ids}).catch(function(){})}
          }catch(e){}
          return f.apply(this,arguments)};
        w.__zad=1;window.ntFire=w};

      /* الضغط على التنبيه يفتح الذكر المناسب */
      var openTries=0;
      var openAct=function(a){
        if(typeof ntOpen==="function"&&document.readyState==="complete"){try{ntOpen(a)}catch(e){}return}
        if(openTries++<40)setTimeout(function(){openAct(a)},500)};
      LN.addListener("localNotificationActionPerformed",function(ev){
        var x=ev&&ev.notification&&ev.notification.extra;if(x&&x.a)openAct(x.a)});

      var later=function(ms){clearTimeout(timer);timer=setTimeout(schedule,ms)};
      var hook=function(){
        wrapFire();
        refreshPerm().then(function(){later(1500)});
        document.addEventListener("visibilitychange",function(){if(document.visibilityState==="hidden")later(300);else refreshPerm().then(function(){later(2500)})});
        var si=Storage.prototype.setItem;
        Storage.prototype.setItem=function(k){var r=si.apply(this,arguments);if(k==="zad"&&!SIM)later(4000);return r};
        setInterval(schedule,6*36e5)};
      if(document.readyState==="complete")setTimeout(hook,2500);else addEventListener("load",function(){setTimeout(hook,2500)});
      NS.schedule=schedule;NS.simulate=simulate;NS.perm=function(){return perm};NS.ask=ask;
      NS.status=function(){
        return Promise.all([refreshPerm(),LN.getPending().then(function(r){return(r&&r.notifications)||[]},function(){return[]})]).then(function(a){
          var pend=a[1],nx=0;
          pend.forEach(function(n){var t=n&&n.schedule&&n.schedule.at?new Date(n.schedule.at).getTime():0;if(t&&(!nx||t<nx))nx=t});
          var txt="";try{if(nx)txt=new Date(nx).toLocaleString("ar",{weekday:"short",hour:"numeric",minute:"2-digit"})}catch(e){}
          return{native:true,ln:true,ms:!!plug("MediaSession"),geo:!!plug("Geolocation"),perm:a[0],pending:pend.length,next:txt}})};
      NS.testNotify=function(){
        return(perm==="granted"?Promise.resolve(perm):ask()).then(function(p){
          if(p!=="granted"){try{window.qtoast("إذن الإشعارات غير ممنوح")}catch(e){}return}
          return ensureChannel().then(function(){
            return LN.schedule({notifications:[build("زاد المسلم 🔔","اختبار الإشعار: إن رأيته في الستارة فالتنبيهات تعمل ✓","nt","test",Date.now()+5000,hash("test|"+Date.now()))]})
          }).then(function(){try{window.qtoast("سيصل إشعار بعد 5 ثوانٍ — اسحب الستارة",5000)}catch(e){}})
        }).catch(function(){})};
      /* إذن الإشعارات يُطلب من «نافذة الأذونات» في أول تشغيل فقط (pmGate) ولا يُعاد طلبه عند كل دخول */
    });

    /* ===== 4) التحكم الصوتي في الستارة (القرآن وحصن المسلم) عبر Media Session ===== */
    var MS=plug("MediaSession");
    if(MS)safe(function(){
      var st={metadata:null,playbackState:"none"},handlers={},reg={},pt=null,lastAct=0;
      if(typeof window.MediaMetadata==="undefined"){window.MediaMetadata=function(o){o=o||{};this.title=o.title||"";this.artist=o.artist||"";this.album=o.album||"";this.artwork=o.artwork||[]}}
      var call=function(n,a,b){try{var p=MS[n](a,b);if(p&&p.catch)p.catch(function(){})}catch(e){}};
      var send=function(v){clearTimeout(pt);pt=null;if(st.playbackState!==v){st.playbackState=v;call("setPlaybackState",{playbackState:v})}};
      /* هل هناك جلسة صوت نشطة؟ وهل الصوت يعمل فعلًا؟ — نقرأ ذلك من عناصر الصوت نفسها لا من نسخة مخزّنة */
      var active=function(){try{return !!((typeof AP!=="undefined"&&AP.on)||(typeof HP!=="undefined"&&HP.on)||(typeof KA!=="undefined"&&!KA.paused))}catch(e){return false}};
      var real=function(){try{
        if(typeof AP!=="undefined"&&AP.on)return !AU.paused||!!(typeof ZR!=="undefined"&&ZR.q&&ZR.q.tm);
        if(typeof HP!=="undefined"&&HP.on)return !HAU.paused||!!(typeof ZR!=="undefined"&&ZR.h&&ZR.h.tm);
        return typeof KA!=="undefined"&&!KA.paused}catch(e){return false}};
      /* مزامنة أيقونة الإشعار مع الحالة الفعلية. force=true بعد ضغطة زر الستارة: نُرسل فورًا دون تأخير */
      var sync=function(force){
        if(!active())return;
        if(real()){send("playing");return}
        if(force||Date.now()-lastAct<2000){send("paused");return}
        if(!pt)pt=setTimeout(function(){pt=null;if(active()&&!real())send("paused")},2500)};
      var ms={
        get metadata(){return st.metadata},
        set metadata(m){
          st.metadata=m||null;
          if(m)call("setMetadata",{title:String(m.title||""),artist:String(m.artist||""),album:String(m.album||""),artwork:[]})},
        get playbackState(){return st.playbackState},
        set playbackState(v){
          v=v==="playing"||v==="paused"?v:"none";
          if(v==="playing"){send("playing");return}
          if(v==="paused"){
            /* بين آية وآية يقفز التطبيق لحظة إلى «متوقف»؛ لو أرسلناها فورًا لأسقط أندرويد الخدمة الأمامية،
               فنؤخرها ثانيتين ونصف ونلغيها إن عاد التشغيل — إلا إذا كان الإيقاف من ضغطة المستخدم فنُرسلها فورًا */
            if(st.playbackState==="none"){return}
            if(Date.now()-lastAct<2000){send("paused");return}
            clearTimeout(pt);
            pt=setTimeout(function(){pt=null;if(!active()||!real())send("paused")},2500);
            return}
          send("none")},
        setActionHandler:function(a,f){
          handlers[a]=f||null;
          if(f&&!reg[a]){
            reg[a]=1;
            call("setActionHandler",{action:a},function(d){
              lastAct=Date.now();
              var h=handlers[a];
              if(h){try{h(d||{action:a})}catch(e){}}
              if(a==="stop"){try{window.__zadStopAll&&window.__zadStopAll()}catch(e){}}
              /* بعد التشغيل/الإيقاف نُعيد مزامنة الأيقونة مع الحالة الحقيقية عدة مرات، فلا يبقى الزر «ميتًا» أبدًا */
              if(a==="play"||a==="pause"||a==="stop"){[40,300,900,2200].forEach(function(t){setTimeout(function(){sync(true)},t)})}})}},
        setPositionState:function(s){
          if(s&&isFinite(s.duration)&&s.duration>0)call("setPositionState",{duration:s.duration,playbackRate:s.playbackRate||1,position:Math.max(0,s.position||0)})}
      };
      try{Object.defineProperty(navigator,"mediaSession",{value:ms,configurable:true})}catch(e){}
      /* حارس دوري: كل ثانية يُصحّح أيقونة الإشعار إن اختلفت عن الواقع */
      setInterval(function(){try{sync(false)}catch(e){}},1000);
      ms.lastAct=function(){return lastAct};window.__zadNative=window.__zadNative||{};window.__zadNative.media=ms;
    });
  }

  boot();
  if(!done){document.addEventListener("DOMContentLoaded",boot);addEventListener("load",boot)}
})();

/* ===== الستارة: زر «إيقاف» حقيقي للقرآن وحصن المسلم، يعمل دون فتح التطبيق ===== */
(function(){
  "use strict";
  if(location.hostname!=="localhost")return;
  var shown=false,lastT="";
  var ref=function(f){try{return f()}catch(e){return null}};
  /* يوقف كل صوت ويزيل إشعارَي الوسائط والإيقاف. يستدعيه زر «إيقاف» (أصليًا) ومعالج stop */
  window.__zadStopAll=function(){
    ref(function(){if(typeof AP!=="undefined"&&AP.on)qstop()});
    ref(function(){if(typeof HP!=="undefined"&&HP.on)hstop()});
    ref(function(){if(typeof AU!=="undefined")AU.pause()});
    ref(function(){if(typeof HAU!=="undefined")HAU.pause()});
    ref(function(){if(typeof KA!=="undefined")KA.pause()});
    ref(function(){if(typeof msClear==="function")msClear()});
    ref(function(){if(window.ZadShade)ZadShade.hide()});
    shown=false;lastT="";return 1};
  var title=function(){return ref(function(){
    if(typeof AP!=="undefined"&&AP.on&&typeof QD!=="undefined"&&QD&&QD.names)return"سورة "+QD.names[AP.s-1];
    if(typeof HP!=="undefined"&&HP.on)return"حصن المسلم";
    return""})||""};
  var active=function(){return !!ref(function(){return(typeof AP!=="undefined"&&AP.on)||(typeof HP!=="undefined"&&HP.on)})};
  /* قفل CPU جزئي أثناء التشغيل الفعلي فقط (الـ WebView لا يمسك قفلًا بنفسه، فقد ينام المعالج والشاشة مطفأة).
     يُترك 30 ثانية بعد الإيقاف المؤقت ثم يُفلَت لئلا يستنزف البطارية */
  var pz=0,wl=false;
  var hold=function(){
    if(!active()){pz=0;return false}
    var r=ref(function(){return(typeof AU!=="undefined"&&AP.on&&(!AU.paused||(typeof ZR!=="undefined"&&ZR.q&&ZR.q.tm)))||(typeof HAU!=="undefined"&&HP.on&&(!HAU.paused||(typeof ZR!=="undefined"&&ZR.h&&ZR.h.tm)))});
    if(r){pz=0;return true}
    if(!pz)pz=Date.now();
    return Date.now()-pz<30000};
  var sync=function(){
    if(!window.ZadShade)return;
    try{var hd=hold();if(hd!==wl){wl=hd;if(ZadShade.setPlaying)ZadShade.setPlaying(hd);if(window.__zlog)window.__zlog("wakelock "+(hd?"on":"off"))}}catch(e){}
    if(active()){
      var t=title();
      if(!shown||t!==lastT){shown=true;lastT=t;ref(function(){ZadShade.show(t||"زاد المسلم")})}
    }else if(shown){shown=false;lastT="";ref(function(){ZadShade.hide()})}};
  setInterval(sync,1500);
  document.addEventListener("visibilitychange",sync);
})();

/* ===== سجل تشخيص الصوت (يُعرض من: إعدادات القرآن ← اختبار الإشعارات ← سجل الصوت) ===== */
(function(){
  "use strict";
  var KEY="zadlog",buf=[];
  try{buf=JSON.parse(localStorage.getItem(KEY)||"[]");if(!Array.isArray(buf))buf=[]}catch(e){buf=[]}
  var p2=function(x){return(x<10?"0":"")+x};
  var ts=function(){var d=new Date();return p2(d.getMonth()+1)+"-"+p2(d.getDate())+" "+p2(d.getHours())+":"+p2(d.getMinutes())+":"+p2(d.getSeconds())};
  var log=function(m){try{buf.push(ts()+" "+m);if(buf.length>600)buf=buf.slice(-450);localStorage.setItem(KEY,JSON.stringify(buf))}catch(e){}};
  window.__zlog=log;
  window.__zlogGet=function(n){return buf.slice(-(n||150)).join("\n")};
  window.__zlogClear=function(){buf=[];try{localStorage.removeItem(KEY)}catch(e){}};
  var lastUser=0;
  ["touchstart","mousedown","keydown"].forEach(function(e){document.addEventListener(e,function(){lastUser=Date.now()},true)});
  var lastActOf=function(){try{var m=window.__zadNative&&window.__zadNative.media;return(m&&m.lastAct)?m.lastAct():0}catch(e){return 0}};
  var st=function(){try{return"AP="+((typeof AP!=="undefined"&&AP.on)?AP.s+":"+AP.a:"-")+" HP="+((typeof HP!=="undefined"&&HP.on)?1:0)+" vis="+document.visibilityState}catch(e){return"?"}};
  var as=function(a){try{return"t="+(a.currentTime||0).toFixed(1)+" rs="+a.readyState+" ns="+a.networkState+" pz="+(a.paused?1:0)+" en="+(a.ended?1:0)}catch(e){return"?"}};
  var userAgo=function(){var t=Math.max(lastUser,lastActOf());return t?Math.round((Date.now()-t)/1000):-1};

  log("START "+st());
  try{if(window.ZadDiag){String(ZadDiag.info()).split("\n").forEach(function(l){if(l)log("NATIVE "+l)})}}catch(e){}

  ["visibilitychange","pagehide","pageshow","freeze","resume","online","offline"].forEach(function(e){
    (e==="visibilitychange"?document:window).addEventListener(e,function(){log("EVT "+e+" "+st())},true)});

  var hooked=false;
  var hook=function(){
    if(hooked)return;
    if(typeof AU==="undefined"||typeof HAU==="undefined"||typeof KA==="undefined"||typeof qstop!=="function"||typeof hstop!=="function"||typeof msClear!=="function")return;
    hooked=true;
    [["AU",AU],["HAU",HAU],["KA",KA]].forEach(function(x){
      var k=x[0],a=x[1];
      ["pause","ended","error","stalled","waiting","emptied"].forEach(function(e){
        a.addEventListener(e,function(){
          try{
            if(e==="pause"&&a.ended)return;
            var extra="";
            if(e==="error"){extra=" code="+((a.error&&a.error.code)||0)}
            if(e==="pause"&&k!=="KA"){extra=" userAgo="+userAgo()+"s"}
            log(k+" "+e+extra+" "+as(a)+" "+st())
          }catch(_){}
        })})});
    ["qstop","hstop","msClear"].forEach(function(n){
      var o=window[n];if(typeof o!=="function"||o.__zw)return;
      var w=function(){var c="";try{c=(new Error().stack||"").split("\n").slice(2,4).join("|").replace(/\s+/g," ").slice(0,200)}catch(e){}
        log("CALL "+n+" "+st()+" @"+c);return o.apply(this,arguments)};
      w.__zw=1;window[n]=w});
    log("hooked");
  };
  var hk=setInterval(function(){hook();if(hooked)clearInterval(hk)},400);

  /* حارس: إن بقي العنصر في حالة «انتهى» دون أن ينتقل للآية التالية أكثر من 3.5 ثوانٍ نُكمل يدويًا */
  var eq=0,eh=0,hb=0;
  setInterval(function(){
    try{
      if(!hooked)return;
      if(AP.on&&AU.ended){if(!eq)eq=Date.now();else if(Date.now()-eq>3500){eq=0;log("stuck-ended Q -> anext");anext()}}else eq=0;
      if(HP.on&&HAU.ended){if(!eh)eh=Date.now();else if(Date.now()-eh>3500){eh=0;log("stuck-ended H -> hnext");hnext()}}else eh=0;
      if((AP.on||HP.on)&&++hb>=60){hb=0;log("HB "+(AP.on?"AU "+as(AU):"HAU "+as(HAU))+" KA "+as(KA)+" "+st())}
      if(!(AP.on||HP.on))hb=0;
    }catch(e){}
  },1000);

  /* شاشة عرض السجل */
  window.zlogShow=function(){
    try{
      qshow("qo-zl","سجل الصوت",'<div class="card"><div class="mu" style="font-size:.8em;margin-bottom:8px">بعد أن يتوقف الصوت: افتح هذه الصفحة، اضغط «نسخ السجل» ثم الصقه في المحادثة.</div><textarea id="zlt" readonly style="width:100%;height:50vh;direction:ltr;text-align:left;font-size:11px;font-family:monospace"></textarea><button class="gdl" style="width:100%;margin-top:8px" onclick="zlogCopy()">📋 نسخ السجل</button><button class="gdl" style="width:100%;margin-top:8px" onclick="__zlogClear();zlogShow()">🗑 مسح السجل</button></div>');
      var ta=document.getElementById("zlt");if(ta){ta.value=window.__zlogGet(150);ta.scrollTop=ta.scrollHeight}
    }catch(e){}
  };
  window.zlogCopy=function(){
    try{var ta=document.getElementById("zlt");ta.focus();ta.select();ta.setSelectionRange(0,9999999);
      var ok=document.execCommand("copy");
      if(typeof qtoast==="function")qtoast(ok?"تم نسخ السجل":"حدّد النص يدويًا ثم انسخه")}catch(e){}
  };
})();
