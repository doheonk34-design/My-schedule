const CACHE_NAME = 'shift-note-cache-v5';
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
    caches.open(CACHE_NAME).then((cache) => cache.addAll(ASSETS.map((u) => new Request(u, { cache: 'reload' }))))
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

// 새 버전 알림: 지금 막 열리는 화면은 아직 준비가 안 됐을 수 있어서, 잠깐씩 기다리며 몇 번 시도한다.
async function notifyUpdate(resultingClientId) {
  for (let i = 0; i < 20; i++) {
    const target = resultingClientId ? await self.clients.get(resultingClientId) : null;
    if (target) {
      target.postMessage({ type: 'shiftnote-update' });
      return;
    }
    if (!resultingClientId) break;
    await new Promise((r) => setTimeout(r, 500));
  }
  const clients = await self.clients.matchAll({ type: 'window' });
  clients.forEach((c) => c.postMessage({ type: 'shiftnote-update' }));
}

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
            if (res.ok || res.type === 'opaque') cache.put(req, res.clone());
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
        // 캐시 응답은 곧 화면에 넘겨져 본문이 소비되므로, 비교용 텍스트는 지금 미리 읽어둔다
        const cachedTextPromise = cached ? cached.clone().text() : Promise.resolve(null);
        // 페이지 이동(navigate) 요청 객체에 옵션을 붙여 fetch하면 크롬 등에서 오류가 나서
        // (예전 코드는 이 오류가 조용히 무시돼 새 버전을 영영 못 받았음) 주소 문자열로 다시 요청한다.
        const networkFetch = fetch(req.url, { cache: 'no-store', credentials: 'same-origin' })
          .then(async (res) => {
            if (!res.ok) return cached ? null : res; // 404/500 같은 응답으로 정상 캐시를 덮지 않음
            const forCache = res.clone();
            if (cached) {
              // 받아온 화면이 지금 캐시와 다르면 = 새 버전 배포됨 → 열린 화면에 알림
              const [oldText, newText] = await Promise.all([cachedTextPromise, res.clone().text()]);
              await cache.put(req, forCache);
              if (oldText !== newText) await notifyUpdate(event.resultingClientId);
            } else {
              await cache.put(req, forCache);
            }
            return res;
          })
          .catch(() => null);

        if (cached) {
          event.waitUntil(networkFetch); // 백그라운드 갱신이 끝날 때까지 서비스 워커 유지
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
        if (res.ok) {
          const resClone = res.clone();
          caches.open(CACHE_NAME).then((cache) => cache.put(req, resClone));
        }
        return res;
      });
    })
  );
});
