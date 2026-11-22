// Stencil Artist service worker: makes the app installable and usable offline.
//
// Everything is fetched relative to this file, so the app works from a sub-path
// (GitHub Pages serves it at /<repository>/).
//
// Two kinds of file, two rules:
//  - HEAVY files (the WebAssembly binaries, the ONNX runtime, the models) are tens of megabytes and
//    change only with a release. Cache first: once fetched they are never fetched again, and they
//    are cached when first used, not up front, so nobody downloads a model they never run.
//  - The SHELL (index.html, the app's JavaScript, the manifest, icons) is small. Network first, so
//    a new release is picked up on the next visit; the cache is the fallback when offline.
//
// Bump VERSION when a heavy file changes under the same name (a model is replaced, the ONNX
// runtime is upgraded). The app's own .wasm files are content-hashed and need no bump.
const VERSION = 'v2';
const SHELL = `stencil-artist-shell-${VERSION}`;
const HEAVY = `stencil-artist-heavy-${VERSION}`;

const SHELL_FILES = ['./', 'index.html', 'stencil-artist.js', 'manifest.webmanifest',
  'icons/icon-192.png', 'icons/icon-512.png'];

const isHeavy = (url) => /\.(wasm|onnx)$/.test(url.pathname) || url.pathname.includes('/ort/');

self.addEventListener('install', (event) => {
  // The shell is fetched up front so the very first offline visit has something to show. A file
  // that fails to fetch does not fail the install: the app still works online.
  event.waitUntil(
    caches.open(SHELL)
      .then((cache) => Promise.all(SHELL_FILES.map((file) => cache.add(file).catch(() => undefined))))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  // Drop caches left by earlier versions.
  event.waitUntil(
    caches.keys()
      .then((names) => Promise.all(names.filter((n) => n !== SHELL && n !== HEAVY).map((n) => caches.delete(n))))
      .then(() => self.clients.claim())
  );
});

async function cacheFirst(request) {
  const cache = await caches.open(HEAVY);
  const hit = await cache.match(request);
  if (hit) return hit;
  const response = await fetch(request);
  // Only complete, successful responses are kept (not a 206 range or an opaque error).
  if (response.status === 200) cache.put(request, response.clone());
  return response;
}

async function networkFirst(request) {
  const cache = await caches.open(SHELL);
  try {
    const response = await fetch(request);
    if (response.status === 200) cache.put(request, response.clone());
    return response;
  } catch (offline) {
    const hit = await cache.match(request, { ignoreSearch: true });
    if (hit) return hit;
    // A navigation to any path inside the app falls back to the app itself.
    if (request.mode === 'navigate') {
      const shell = await cache.match('index.html');
      if (shell) return shell;
    }
    throw offline;
  }
}

self.addEventListener('fetch', (event) => {
  const request = event.request;
  const url = new URL(request.url);
  // Cache just the approved on-demand depth model across sessions. Other cross-origin requests
  // are never intercepted; background isolation and normal web traffic are unchanged.
  const depthModel = url.hostname === 'huggingface.co' &&
    url.pathname === '/onnx-community/depth-anything-v2-small/resolve/main/onnx/model_quantized.onnx';
  if (request.method !== 'GET') return;
  if (depthModel) {
    event.respondWith(cacheFirst(request));
  } else if (url.origin === self.location.origin) {
    event.respondWith(isHeavy(url) ? cacheFirst(request) : networkFirst(request));
  }
});
