const CACHE = "between-worlds-pwa-v1";
const CORE = ["/", "/manifest.webmanifest", "/icon.svg", "/jlz-welcome-portrait.webp", "/jlz-home-portrait.webp"];

self.addEventListener("install", event => {
  event.waitUntil(caches.open(CACHE).then(cache => cache.addAll(CORE)).then(() => self.skipWaiting()));
});

self.addEventListener("activate", event => {
  event.waitUntil(
    caches.keys().then(keys => Promise.all(keys.filter(key => key !== CACHE).map(key => caches.delete(key))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", event => {
  const req = event.request;
  if (req.method !== "GET" || new URL(req.url).origin !== self.location.origin) return;
  if (req.mode === "navigate") {
    event.respondWith(fetch(req).then(res => {
      const copy = res.clone();
      caches.open(CACHE).then(cache => cache.put("/", copy));
      return res;
    }).catch(() => caches.match("/")));
    return;
  }
  event.respondWith(
    caches.match(req).then(cached => cached || fetch(req).then(res => {
      if (res.ok) caches.open(CACHE).then(cache => cache.put(req, res.clone()));
      return res;
    }))
  );
});

self.addEventListener("push", event => {
  let data = {};
  try { data = event.data ? event.data.json() : {}; } catch { data = { body: event.data?.text() || "来看看我。" }; }
  const title = data.title || "纪临洲";
  const options = {
    body: data.body || "来看看我。",
    icon: "/jlz-home-portrait.webp",
    badge: "/jlz-home-portrait.webp",
    tag: data.tag || "between-worlds-message",
    renotify: true,
    data: { url: data.url || "/#echo" }
  };
  event.waitUntil(self.registration.showNotification(title, options));
});

self.addEventListener("notificationclick", event => {
  event.notification.close();
  const target = event.notification.data?.url || "/#echo";
  event.waitUntil(
    self.clients.matchAll({ type: "window", includeUncontrolled: true }).then(clients => {
      for (const client of clients) {
        if ("focus" in client) {
          client.navigate(target);
          return client.focus();
        }
      }
      return self.clients.openWindow(target);
    })
  );
});
