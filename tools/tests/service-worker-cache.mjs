// Regression tests for the real service worker, with controlled fetch and Cache Storage.
// Run with: node tools/tests/service-worker-cache.mjs
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const script = readFileSync("composeApp/src/wasmJsMain/resources/sw.js", "utf8");

async function fetchThroughServiceWorker(url, { cached = null, networkStatus = 200, failPut = false } = {}) {
  const handlers = {};
  const successful = { status: networkStatus, clone() { return this; } };
  let networkCalls = 0;
  let cacheWrites = 0;
  const cache = {
    match: async () => cached,
    put: async () => {
      cacheWrites++;
      if (failPut) throw new Error("QuotaExceededError");
    },
  };
  const self = {
    location: { origin: "https://stencil.example" },
    addEventListener(name, handler) { handlers[name] = handler; },
  };
  vm.runInNewContext(script, {
    self,
    URL,
    caches: { open: async () => cache },
    fetch: async () => {
      networkCalls++;
      return successful;
    },
  });
  let result;
  handlers.fetch({
    request: { method: "GET", url },
    respondWith(promise) { result = promise; },
  });
  assert.ok(result, "This request must be intercepted by the service worker");
  return { response: await result, successful, networkCalls, cacheWrites };
}

const wasm = "https://stencil.example/stencil-artist/ort/ort-wasm-simd-threaded.wasm";
const model = "https://huggingface.co/onnx-community/depth-anything-v2-small/resolve/f7421df0cc30f121782ab050d42f0a423291dcde/onnx/model_quantized.onnx";

for (const url of [wasm, model]) {
  const out = await fetchThroughServiceWorker(url, { failPut: true });
  assert.equal(out.response.status, 200, "A quota failure must not mask a successful download");
  assert.strictEqual(out.response, out.successful);
  assert.equal(out.networkCalls, 1);
  assert.equal(out.cacheWrites, 1);
}
const cacheHit = { status: 200 };
const hit = await fetchThroughServiceWorker(wasm, { cached: cacheHit });
assert.strictEqual(hit.response, cacheHit);
assert.equal(hit.networkCalls, 0, "Cached model should not be redownloaded");

const absent = await fetchThroughServiceWorker(model, { networkStatus: 404 });
assert.equal(absent.response.status, 404);
assert.equal(absent.cacheWrites, 0, "Never cache failed downloads");

// The paint catalogue is small but, like the models, changes only with a release: cache first.
const catalogue = "https://stencil.example/stencil-artist/models/paints.tsv";
const fetched = await fetchThroughServiceWorker(catalogue, { failPut: true });
assert.equal(fetched.response.status, 200, "A quota failure must not mask a successful catalogue download");
assert.equal(fetched.networkCalls, 1);
assert.equal(fetched.cacheWrites, 1, "The catalogue is cached in the heavy cache on first use");
const catalogueHit = await fetchThroughServiceWorker(catalogue, { cached: cacheHit });
assert.strictEqual(catalogueHit.response, cacheHit);
assert.equal(catalogueHit.networkCalls, 0, "A cached catalogue is not fetched again");
const missing = await fetchThroughServiceWorker(catalogue, { networkStatus: 404 });
assert.equal(missing.cacheWrites, 0, "Never cache a failed catalogue download");

// The barcode reader (zxing-wasm, loaded on first scan) is heavy and fixed per release: cache first,
// the JavaScript and licence beside the wasm included, quota failure or not.
for (const file of ["zxing_reader.wasm", "zxing_reader.js", "LICENSE"]) {
  const url = `https://stencil.example/stencil-artist/zxing/${file}`;
  const first = await fetchThroughServiceWorker(url, { failPut: true });
  assert.equal(first.response.status, 200, `A quota failure must not mask the ${file} download`);
  assert.equal(first.networkCalls, 1);
  assert.equal(first.cacheWrites, 1, `${file} is cached in the heavy cache on first use`);
  const again = await fetchThroughServiceWorker(url, { cached: cacheHit });
  assert.strictEqual(again.response, cacheHit);
  assert.equal(again.networkCalls, 0, `A cached ${file} is not fetched again`);
  const gone = await fetchThroughServiceWorker(url, { networkStatus: 404 });
  assert.equal(gone.cacheWrites, 0, `Never cache a failed ${file} download`);
}

// The scanner glue is part of the shell: network first, so a release is picked up on the next visit.
const glue = await fetchThroughServiceWorker("https://stencil.example/stencil-artist/scanner.js", { cached: cacheHit });
assert.equal(glue.networkCalls, 1, "scanner.js is network first");

// Only the zxing/ directory is heavy, not any file with zxing in its name.
const zxingLookalike = await fetchThroughServiceWorker("https://stencil.example/stencil-artist/zxing.html", { cached: cacheHit });
assert.equal(zxingLookalike.networkCalls, 1);

// The shell stays network first: a cached page does not stop the network being asked.
const page = await fetchThroughServiceWorker("https://stencil.example/stencil-artist/index.html", { cached: cacheHit });
assert.equal(page.networkCalls, 1, "The app shell is network first");

// A file that merely has tsv in its name is not the catalogue.
const lookalike = await fetchThroughServiceWorker("https://stencil.example/stencil-artist/tsv.html", { cached: cacheHit });
assert.equal(lookalike.networkCalls, 1);

console.log("Service worker cache tests passed");
