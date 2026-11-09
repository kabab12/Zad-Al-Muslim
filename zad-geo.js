
/* ======== الموقع في أندرويد: بديل navigator.geolocation عبر إضافة Capacitor Geolocation ======== */
(function(){try{
 if(!(window.Capacitor&&Capacitor.isNativePlatform&&Capacitor.isNativePlatform()))return;
 const G=Capacitor.Plugins&&Capacitor.Plugins.Geolocation;if(!G)return;
 const code=e=>{const m=String((e&&(e.message||e.errorMessage))||e||"");return /denied|permission|not granted/i.test(m)?1:/timeout|timed out/i.test(m)?3:2};
 const okPerm=p=>p&&(p.location==="granted"||p.coarseLocation==="granted");
 async function ensure(){let p=await G.checkPermissions();if(!okPerm(p))p=await G.requestPermissions({permissions:["location","coarseLocation"]});return okPerm(p)}
 const pos=p=>({coords:p.coords,timestamp:p.timestamp||Date.now()});
 const geo={
  getCurrentPosition(ok,err,o){(async()=>{try{
    if(!(await ensure())){err&&err({code:1,message:"denied"});return}
    const p=await G.getCurrentPosition({enableHighAccuracy:!(o&&o.enableHighAccuracy===false),timeout:(o&&o.timeout)||20000,maximumAge:(o&&o.maximumAge)||0});
    ok&&ok(pos(p))}catch(e){err&&err({code:code(e),message:String(e&&e.message||e)})}})()},
  watchPosition(ok,err,o){let on=true;const id=setInterval(()=>{if(!on)return;geo.getCurrentPosition(p=>{on&&ok&&ok(p)},e=>{on&&err&&err(e)},o)},6000);geo.getCurrentPosition(p=>{on&&ok&&ok(p)},e=>{on&&err&&err(e)},o);
    geo._w=geo._w||{};geo._w[id]=()=>{on=false};return id},
  clearWatch(id){try{clearInterval(id);geo._w&&geo._w[id]&&geo._w[id]()}catch(e){}}
 };
 Object.defineProperty(navigator,"geolocation",{value:geo,configurable:true});
 window.zGeoState=async function(){try{const p=await G.checkPermissions();return okPerm(p)?"granted":(p.location==="denied"&&p.coarseLocation==="denied")?"denied":"prompt"}catch(e){return"prompt"}};
}catch(e){}})();
