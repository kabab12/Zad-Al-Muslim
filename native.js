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
      var ensureChannel=function(){
        if(!chanP)chanP=LN.createChannel({id:"zad_reminders",name:"تذكيرات زاد المسلم",description:"الأذكار والصلاة على النبي والأذان والورد",importance:4,visibility:1,vibration:true,lights:true}).catch(function(){});
        return chanP};
      var clip=function(s,n){s=String(s||"");return s.length>n?s.slice(0,n-1).replace(/\s+\S*$/,"")+"…":s};
      var build=function(title,body,act,tag,atMs,id){
        return{id:id,title:String(title||"زاد المسلم"),body:clip(body,600),largeBody:String(body||""),summaryText:"زاد المسلم",
          schedule:{at:new Date(atMs),allowWhileIdle:true},channelId:"zad_reminders",smallIcon:"ic_stat_zad",extra:{a:act||"nt",tag:String(tag||"")}}};
      var fireNow=function(title,opts){
        opts=opts||{};
        var tag=opts.tag||"",m=Math.floor(Date.now()/6e4);
        if(tag&&(SCH[zid(tag,m)]||SCH[zid(tag,m-1)]))return;
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

      var schedule=function(){
        if(busy){again=true;return}
        if(perm!=="granted"||typeof S==="undefined"||typeof ntTick!=="function")return;
        busy=true;
        var plan=[];try{plan=simulate(3,15)}catch(e){plan=[]}
        var now=Date.now(),seen={},items=[];
        plan.filter(function(x){return x.at>now+4000}).sort(function(a,b){return a.at-b.at}).forEach(function(x){
          var m=Math.floor(x.at/6e4),id=zid(x.tag,m);
          if(seen[id])return;seen[id]=1;
          if(items.length<240)items.push(build(x.title,x.body,x.act,x.tag,x.at,id))});
        ensureChannel().then(function(){return LN.getPending()}).then(function(r){
          var p=(r&&r.notifications)||[];
          return p.length?LN.cancel({notifications:p.map(function(n){return{id:n.id}})}):0
        }).then(function(){
          var ch=Promise.resolve();
          for(var i=0;i<items.length;i+=40)(function(part){ch=ch.then(function(){return LN.schedule({notifications:part})})})(items.slice(i,i+40));
          return ch
        }).then(function(){SCH=seen}).catch(function(){}).then(function(){busy=false;if(again){again=false;setTimeout(schedule,2500)}})};

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
            if(!SIM&&document.visibilityState==="visible"&&tag){
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
      /* طلب الإذن مرة واحدة تلقائيًا عند أول تشغيل (على أندرويد 13+ يلزمه إشعار الصوت أيضًا) */
      safe(function(){
        var k="zadNtAsk",asked=false;try{asked=!!localStorage.getItem(k)}catch(e){}
        var go=function(){refreshPerm().then(function(p){
          if(p==="default"&&!asked){try{localStorage.setItem(k,"1")}catch(e){}setTimeout(ask,1500)}
          else if(p==="denied"){try{window.qtoast("الإشعارات متوقفة: فعّلها من إعدادات الهاتف ← التطبيقات ← زاد المسلم ← الإشعارات",6000)}catch(e){}}})};
        if(document.readyState==="complete")setTimeout(go,4500);else addEventListener("load",function(){setTimeout(go,4500)})});
    });

    /* ===== 4) التحكم الصوتي في الستارة (القرآن وحصن المسلم) عبر Media Session ===== */
    var MS=plug("MediaSession");
    if(MS)safe(function(){
      var st={metadata:null,playbackState:"none"},handlers={},reg={},pt=null;
      if(typeof window.MediaMetadata==="undefined"){window.MediaMetadata=function(o){o=o||{};this.title=o.title||"";this.artist=o.artist||"";this.album=o.album||"";this.artwork=o.artwork||[]}}
      var call=function(n,a,b){try{var p=MS[n](a,b);if(p&&p.catch)p.catch(function(){})}catch(e){}};
      var ms={
        get metadata(){return st.metadata},
        set metadata(m){
          st.metadata=m||null;
          if(m)call("setMetadata",{title:String(m.title||""),artist:String(m.artist||""),album:String(m.album||""),artwork:[]})},
        get playbackState(){return st.playbackState},
        set playbackState(v){
          v=v==="playing"||v==="paused"?v:"none";
          /* بين آية وآية يقفز التطبيق لحظة إلى «متوقف»؛ لو أرسلناها لأندرويد لأسقط الخدمة الأمامية
             وتوقف الصوت في الخلفية، فنؤخر «متوقف» ثانيتين ونصف ونلغيها إن عاد التشغيل */
          if(v==="playing"){
            clearTimeout(pt);
            if(st.playbackState!=="playing"){st.playbackState="playing";call("setPlaybackState",{playbackState:"playing"})}
            return}
          if(v==="paused"){
            if(st.playbackState!=="playing")return;
            clearTimeout(pt);
            pt=setTimeout(function(){st.playbackState="paused";call("setPlaybackState",{playbackState:"paused"})},2500);
            return}
          clearTimeout(pt);
          if(st.playbackState!=="none"){st.playbackState="none";call("setPlaybackState",{playbackState:"none"})}},
        setActionHandler:function(a,f){
          handlers[a]=f||null;
          if(f&&!reg[a]){
            reg[a]=1;
            call("setActionHandler",{action:a},function(d){var h=handlers[a];if(h){try{h(d||{action:a})}catch(e){}}})}},
        setPositionState:function(s){
          if(s&&isFinite(s.duration)&&s.duration>0)call("setPositionState",{duration:s.duration,playbackRate:s.playbackRate||1,position:Math.max(0,s.position||0)})}
      };
      try{Object.defineProperty(navigator,"mediaSession",{value:ms,configurable:true})}catch(e){}
      window.__zadNative=window.__zadNative||{};window.__zadNative.media=ms;
    });
  }

  boot();
  if(!done){document.addEventListener("DOMContentLoaded",boot);addEventListener("load",boot)}
})();
