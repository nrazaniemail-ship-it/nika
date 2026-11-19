const PREFIX="nika-";
const FILES=["./","index.html","manifest.webmanifest","icon.png"];
async function versionedCacheName(){
 const response=await fetch(self.location.href,{cache:"no-store"});
 if(!response.ok)throw new Error("Cannot read service worker source");
 const source=await response.text();
 const digest=await crypto.subtle.digest("SHA-256",new TextEncoder().encode(source));
 const hash=Array.from(new Uint8Array(digest),b=>b.toString(16).padStart(2,"0")).join("").slice(0,16);
 return PREFIX+hash;
}
self.addEventListener("install",event=>{
 event.waitUntil(versionedCacheName().then(name=>caches.open(name)).then(cache=>cache.addAll(FILES)));
});
self.addEventListener("activate",event=>{
 event.waitUntil(caches.keys().then(names=>Promise.all(names.filter(name=>name.startsWith(PREFIX)).map(name=>caches.delete(name)))).then(()=>versionedCacheName()).then(name=>caches.open(name)).then(cache=>cache.addAll(FILES)).then(()=>self.clients.claim()));
});
self.addEventListener("message",event=>{
 if(event.data&&event.data.type==="SKIP_WAITING")self.skipWaiting();
});
self.addEventListener("fetch",event=>{
 if(event.request.method!=="GET"||new URL(event.request.url).origin!==location.origin)return;
 event.respondWith(caches.keys().then(names=>{
  const current=names.filter(name=>name.startsWith(PREFIX)).sort().pop();
  return current?caches.open(current):null;
 }).then(cache=>fetch(event.request).then(response=>{
  if(response&&response.ok&&cache)cache.put(event.request,response.clone()).catch(()=>{});
  return response;
 }).catch(error=>cache?cache.match(event.request).then(cached=>cached||Promise.reject(error)):Promise.reject(error))));
});
