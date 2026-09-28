const CACHE = 'pos-pwa-v1';
const CORE = ['./','./index.html','./manifest.json','./icons/icon-192.png','./icons/icon-512.png'];
const QR = 'https://cdnjs.cloudflare.com/ajax/libs/qrcodejs/1.0.0/qrcode.min.js';
self.addEventListener('install', event => {
  event.waitUntil((async()=>{
    const cache=await caches.open(CACHE);
    await cache.addAll(CORE);
    try { await cache.add(QR); } catch(e) { /* QR CDN may be unavailable; app still installs */ }
    await self.skipWaiting();
  })());
});
self.addEventListener('activate', event => event.waitUntil(self.clients.claim()));
self.addEventListener('fetch', event => {
  if(event.request.method !== 'GET') return;
  event.respondWith((async()=>{
    const cached=await caches.match(event.request);
    if(cached) return cached;
    try {
      const response=await fetch(event.request);
      if(response && response.ok && new URL(event.request.url).origin===location.origin){
        const cache=await caches.open(CACHE); cache.put(event.request,response.clone());
      }
      return response;
    } catch(e) {
      return caches.match('./index.html');
    }
  })());
});
