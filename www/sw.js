const CACHE_NAME = 'shift-note-cache-v3';
const FONT_CACHE_NAME = 'shift-note-fonts-v1';
const ASSETS = [
  './',
  './index.html',
  './manifest.json',
  './icon-192.png',
  './icon-512.png',
  './icon-maskable-512.png',
  './apple-touch-icon.png'
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => cache.addAll(ASSETS))
  );
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(
        keys
          .filter((k) => k !== CACHE_NAME && k !== FONT_CACHE_NAME)
          .map((k) => caches.delete(k))
      )
    )
  );
  self.clients.claim();
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  const url = new URL(req.url);

  // Google Fonts: cache-first (fonts basically never change, so this makes
  // repeat loads instant and works offline once cached once).
  if (url.hostname === 'fonts.googleapis.com' || url.hostname === 'fonts.gstatic.com') {
    event.respondWith(
      caches.open(FONT_CACHE_NAME).then((cache) =>
        cache.match(req).then((cached) => {
          if (cached) return cached;
          return fetch(req).then((res) => {
            cache.put(req, res.clone());
            return res;
          });
        })
      )
    );
    return;
  }

  const isHTML = req.mode === 'navigate' ||
    (req.method === 'GET' && req.headers.get('accept')?.includes('text/html'));

  if (isHTML) {
    // Stale-while-revalidate: answer INSTANTLY from cache if we have it,
    // and refresh the cache in the background so the *next* launch is up to date.
    // First-ever visit (no cache yet) waits on the network once.
    event.respondWith(
      caches.open(CACHE_NAME).then(async (cache) => {
        const cached = await cache.match(req);
        const networkFetch = fetch(req, { cache: 'no-store' })
          .then((res) => {
            cache.put(req, res.clone());
            return res;
          })
          .catch(() => null);

        if (cached) {
          networkFetch; // fire-and-forget refresh for next time
          return cached;
        }
        return (await networkFetch) || cache.match('./index.html');
      })
    );
    return;
  }

  // Everything else (icons, manifest, etc.): cache-first.
  event.respondWith(
    caches.match(req).then((cached) => {
      if (cached) return cached;
      return fetch(req).then((res) => {
        const resClone = res.clone();
        caches.open(CACHE_NAME).then((cache) => cache.put(req, resClone));
        return res;
      });
    })
  );
});
