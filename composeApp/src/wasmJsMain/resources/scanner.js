// Stencil Artist barcode scanner: the camera and the decoders, for the web build.
//
// Scanner.wasmJs.kt loads this file with a script tag the first time a can is scanned (it is not
// part of the Kotlin bundle) and calls StencilScanner.start. Nothing here knows about Compose.
//
// A session owns a <video> element laid over the page (Compose draws one canvas, which cannot show a
// video), the camera stream, and a decode loop. It reports raw decoded text; the app decides whether
// it is a valid barcode. Two decoders:
//  - the browser's BarcodeDetector, when it lists all four retail formats (Chrome on Android, macOS and
//    ChromeOS; not iPhone Safari, Firefox or desktop Chrome on Windows and Linux);
//  - zxing-wasm (reader build, MIT), loaded from zxing/ next to the page on first use otherwise.
(function () {
  'use strict';

  const RETAIL_BD = ['ean_13', 'ean_8', 'upc_a', 'upc_e'];
  const RETAIL_ZX = ['EAN-13', 'EAN-8', 'UPC-A', 'UPC-E'];
  /** Decode at most this often (about 8 frames a second): a barcode does not need more, a phone's battery does. */
  const FRAME_MS = 125;
  /** Frames are scaled down so their long edge is no more than this before decoding. */
  const MAX_EDGE = 960;

  const absolute = (path) => new URL(path, document.baseURI).href;

  function loadScript(src) {
    return new Promise((resolve, reject) => {
      const script = document.createElement('script');
      script.src = src;
      script.onload = resolve;
      script.onerror = () => reject(new Error('failed to load ' + src));
      document.head.appendChild(script);
    });
  }

  let zxingPromise = null;
  /** zxing-wasm, loaded once. The wasm is found next to the page (so a sub-path such as GitHub Pages works), never on a CDN. */
  function loadZxing() {
    if (!zxingPromise) {
      zxingPromise = (async () => {
        if (!globalThis.ZXingWASM) await loadScript(absolute('zxing/zxing_reader.js'));
        await globalThis.ZXingWASM.prepareZXingModule({
          overrides: { locateFile: (name, prefix) => (name.endsWith('.wasm') ? absolute('zxing/' + name) : prefix + name) },
          fireImmediately: true,
        });
        return globalThis.ZXingWASM;
      })().catch((e) => { zxingPromise = null; throw e; });
    }
    return zxingPromise;
  }

  /** A decoder is { name, decode(canvas, context) -> [{ text, upcE }] }. */
  async function nativeDecoder() {
    if (!globalThis.BarcodeDetector || typeof globalThis.BarcodeDetector.getSupportedFormats !== 'function') return null;
    try {
      const supported = await globalThis.BarcodeDetector.getSupportedFormats();
      if (!RETAIL_BD.every((f) => supported.includes(f))) return null;
      const detector = new globalThis.BarcodeDetector({ formats: RETAIL_BD });
      return {
        name: 'BarcodeDetector',
        decode: async (canvas) =>
          (await detector.detect(canvas)).map((b) => ({ text: b.rawValue, upcE: b.format === 'upc_e' })),
      };
    } catch (_) {
      return null;
    }
  }

  async function zxingDecoder() {
    const zxing = await loadZxing();
    return {
      name: 'zxing-wasm',
      decode: async (canvas, context) => {
        const image = context.getImageData(0, 0, canvas.width, canvas.height);
        const found = await zxing.readBarcodes(image, { formats: RETAIL_ZX, tryHarder: true, maxNumberOfSymbols: 1 });
        return found.filter((r) => r.isValid !== false && r.text).map((r) => ({ text: r.text, upcE: r.format === 'UPC-E' }));
      },
    };
  }

  let pageCanvas = null;
  /** The canvas Compose draws on (it may sit in a shadow root), so the video can be placed relative to it. Null if there is none. */
  function composeCanvas() {
    if (pageCanvas && pageCanvas.isConnected) return pageCanvas;
    pageCanvas = document.querySelector('canvas');
    if (!pageCanvas) {
      for (const el of document.body.querySelectorAll('*')) {
        const found = el.shadowRoot && el.shadowRoot.querySelector('canvas');
        if (found) { pageCanvas = found; break; }
      }
    }
    return pageCanvas;
  }

  /** Which of the app's error kinds a getUserMedia failure is. */
  function cameraError(e) {
    const name = (e && e.name) || '';
    if (name === 'NotAllowedError' || name === 'PermissionDeniedError') return ['permission', ''];
    if (name === 'SecurityError') return ['unsupported', 'The camera is not allowed on this page.'];
    return ['nocamera', (e && e.message) || ''];
  }

  /**
   * Starts a scan and returns at once with the session; the camera and decoder start behind it.
   * onCode(text, upcE) gets every raw read; onError(kind, message) gets 'permission', 'nocamera',
   * 'decoder' or 'unsupported' and ends the session's work (stop() is still safe to call).
   */
  function start(onCode, onError) {
    const box = document.createElement('div');
    box.setAttribute('data-stencil-scanner', '');
    box.style.cssText = 'position:fixed;left:0;top:0;width:0;height:0;display:none;overflow:hidden;' +
      'background:#000;z-index:2147483000;pointer-events:none;';
    const video = document.createElement('video');
    // playsinline and muted are what let iOS Safari show a camera inside the page.
    video.setAttribute('playsinline', '');
    video.setAttribute('muted', '');
    video.setAttribute('autoplay', '');
    video.muted = true;
    video.playsInline = true;
    video.style.cssText = 'width:100%;height:100%;object-fit:cover;display:block;';
    const reticle = document.createElement('div');
    reticle.style.cssText = 'position:absolute;left:12%;right:12%;top:31%;bottom:31%;' +
      'border:1.5px solid rgba(255,255,255,0.85);border-radius:8px;box-shadow:0 0 0 1px rgba(0,0,0,0.45);';
    box.appendChild(video);
    box.appendChild(reticle);
    document.body.appendChild(box);

    const session = { stopped: false, decoder: null, stream: null, frame: 0 };
    let failed = false;
    const fail = (kind, message) => {
      if (session.stopped || failed) return;
      failed = true;
      stop();
      onError(kind, message);
    };

    function stop() {
      session.stopped = true;
      if (session.frame) cancelAnimationFrame(session.frame);
      session.frame = 0;
      if (session.stream) session.stream.getTracks().forEach((t) => t.stop());
      session.stream = null;
      video.pause();
      video.srcObject = null;
      box.remove();
    }

    /** Puts the video over [left, top, width, height] (CSS pixels of the viewport) with the given insets cut off by clipping. */
    function place(left, top, width, height, clipTop, clipRight, clipBottom, clipLeft) {
      if (session.stopped) return;
      const canvas = composeCanvas();
      const origin = canvas ? canvas.getBoundingClientRect() : { left: 0, top: 0 };
      const visible = width > 0 && height > 0 && clipLeft + clipRight < width && clipTop + clipBottom < height;
      box.style.display = visible ? 'block' : 'none';
      box.style.left = origin.left + left + 'px';
      box.style.top = origin.top + top + 'px';
      box.style.width = width + 'px';
      box.style.height = height + 'px';
      box.style.clipPath = 'inset(' + clipTop + 'px ' + clipRight + 'px ' + clipBottom + 'px ' + clipLeft + 'px round 12px)';
    }

    (async () => {
      if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
        fail('unsupported', globalThis.isSecureContext === false
          ? 'The camera needs a secure page (https). Open this app from its https address, or type the barcode.'
          : 'This browser cannot open a camera. Type the barcode instead.');
        return;
      }
      let stream;
      try {
        stream = await navigator.mediaDevices.getUserMedia({
          video: { facingMode: { ideal: 'environment' }, width: { ideal: 1280 } },
          audio: false,
        });
      } catch (e) {
        const [kind, message] = cameraError(e);
        fail(kind, message);
        return;
      }
      if (session.stopped) { stream.getTracks().forEach((t) => t.stop()); return; }
      session.stream = stream;
      video.srcObject = stream;
      try { await video.play(); } catch (_) { /* autoplay is already requested; the loop waits for frames */ }
      if (session.stopped) return;

      try {
        session.decoder = (await nativeDecoder()) || (await zxingDecoder());
      } catch (e) {
        fail('decoder', (e && e.message) || String(e));
        return;
      }
      if (session.stopped) return;
      session.decoderName = session.decoder.name;

      const canvas = document.createElement('canvas');
      const context = canvas.getContext('2d', { willReadFrequently: true });
      let lastAt = 0;
      let busy = false;
      const tick = (now) => {
        if (session.stopped) return;
        session.frame = requestAnimationFrame(tick);
        if (busy || now - lastAt < FRAME_MS || video.readyState < 2 || !video.videoWidth) return;
        lastAt = now;
        busy = true;
        (async () => {
          const scale = Math.min(1, MAX_EDGE / Math.max(video.videoWidth, video.videoHeight));
          canvas.width = Math.max(1, Math.round(video.videoWidth * scale));
          canvas.height = Math.max(1, Math.round(video.videoHeight * scale));
          context.drawImage(video, 0, 0, canvas.width, canvas.height);
          let found;
          try {
            found = await session.decoder.decode(canvas, context);
          } catch (e) {
            if (session.decoder.name === 'BarcodeDetector') {
              // The native detector failed at run time: carry on with zxing-wasm rather than give up.
              try { session.decoder = await zxingDecoder(); session.decoderName = session.decoder.name; } catch (e2) {
                fail('decoder', (e2 && e2.message) || String(e2));
              }
            } else {
              fail('decoder', (e && e.message) || String(e));
            }
            return;
          }
          if (session.stopped) return;
          for (const hit of found) onCode(hit.text, hit.upcE);
        })().finally(() => { busy = false; });
      };
      session.frame = requestAnimationFrame(tick);
    })();

    session.place = place;
    session.stop = stop;
    session.video = video;
    return session;
  }

  globalThis.StencilScanner = { start };
})();
