# StencilPack v1

**Canonical format contract for Stencil Artist, Graffux and GraffitiXR.**

A `.stencilpack` file is a standard ZIP archive (stored or deflated entries). MIME:
`application/vnd.hereliesaz.stencilpack+zip`. Android handoff uses `ACTION_SEND`,
`EXTRA_STREAM` with a grantable `content://` URI, or a SAF file picker.
No private app URI, APK dependency, or external service is required.

## Archive

The ZIP root contains UTF-8 `manifest.json`, transparent `stencil-sheet-NNN.png`
files and an optional `stencil-composite.png`. PNGs are straight-alpha, full-canvas
RGBA, same dimensions as the manifest. Coordinates start at top left, no rotation,
resizing or implicit offsets. Sheet entries are **bottom-to-top paint order**;
their `color` is a straight-alpha `#AARRGGBB` value.

```json
{
  "format": "com.hereliesaz.stencilpack",
  "version": 1,
  "width": 800,
  "height": 600,
  "order": "bottom-to-top",
  "coverage": "overpaint",
  "composite": "stencil-composite.png",
  "layers": [
    {"name": "Sheet 1", "file": "stencil-sheet-001.png", "color": "#ff000000", "visible": true},
    {"name": "Sheet 2", "file": "stencil-sheet-002.png", "color": "#ffffffff", "visible": true}
  ]
}
```

Each sheet PNG contains **only the pixels painted by that sheet**. Later sheets
paint over earlier ones (normal source-over). `coverage="overpaint"` denotes
cuttable overpaint masks, not exclusive colour masks. Importers must not re-run
segmentation or infer paint order from filenames.

## Compatibility and safety

- Validate the exact format identifier and major version. Reject unknown versions,
  missing files, duplicate paths, absolute paths and `..` paths.
- Validate positive dimensions, supported file count and each PNG's decoded size.
  Do not trust ZIP-declared sizes; enforce bounded decompression and a decoded
  pixel budget before allocating bitmaps.
- Preserve layer order, names, colour and visibility. Import as independently
  selectable display layers. All sheets must retain identical alignment.
- Existing PNG and plain ZIP export remain supported independently.
- A receiving app may import a subset only if it clearly explains the omitted
  layers. Silent flattening is **not** a conforming StencilPack import.
- Non-Android implementations may download and open files instead of using
  Android intents.

### v1 producer

Stencil Artist writes one full-resolution transparent PNG per sheet using the
same renderer as its live preview. The package is bounded to 32 MiB of PNG
payload for its in-memory ZIP writer. This contract is independent of
Graffux's `.fux` and GraffitiXR's `.gxr` project formats.
