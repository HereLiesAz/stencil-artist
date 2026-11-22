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

console.log("Service worker cache tests passed");
