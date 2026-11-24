# Lessons

Rules written after corrections. Read at the start of every session on this repository.

## 2026-11-22 — Do not stop between phases

- **What happened:** after an approved multi-phase plan, I ended the turn at phase boundaries to
  report, leaving the next phase unstarted until prompted. Az: "Continue until all are finished.
  I should never catch you waiting around for a result."
- **Rule:** once a plan is approved, run it to the end in one go. Report progress in passing, in a
  line, and keep working. A phase boundary is not a stopping point.
- **Rule:** never idle on something slow (a build, a download, CI). Start it, do other work, come
  back to it.
- **Still stop for:** a decision that is genuinely Az's and blocks everything that remains; an
  action that is destructive or hard to reverse; access that was deliberately withheld.
- _(My reading, not Az's words:)_ the up-front plan check-in still stands; it is the mid-plan
  pauses that were the problem.

## 2026-11-22/23 — Print guidance must not change cuts

- **Clarified 2026-11-23:** Cutting tips may occupy either removed openings **or** surviving stencil stock, wherever safely legible. Earlier stock-only placement was overly restrictive; its original purpose was to prevent annotations crossing or interfering with cuts, **not** to ban notes in removable areas. Painting tips about soft-gradient overspray still prefer uncut stock.
- Check the entire note's physical bounding rectangle plus safety clearance against the full
  sheet's cut mask. A cutting note must fit entirely within one region, not cross its boundary. If it cannot fit, use an adjacent detail on a non-cutting reference page.
- Never draw a pointer, leader, frame, or marker across a cut. Detect islands on the complete
  sheet, not separately on each tile, because tile borders are not physical cut lines.
- Keep the tip adjacent to a cropped depiction of its original cut issue on that reference
  page. Never silently limit or discard detected problem annotations.

## 2026-11-22 — PDF review findings

- Debounced stencil changes invalidate any previously exportable separation **synchronously**;
  a previous cancelled job must not clear the current busy flag.
- Print tips are anchored to pixels on the detected connected component. The chosen component
  pixel also uniquely identifies it across tiled pages; a bounding-box center is insufficient.
- Never retain cut masks and integral-image clearance buffers for every sheet at once. Analyze
  one sheet at a time, then retain only encoded PDF data.
- Bound pathological detail counts before searching for available annotation space. Reject an
  impossible export with a clear error instead of hanging or omitting warnings.
- Calculate candidate safe text positions once per page, not once for every tip.

## 2026-11-22 — Depth serves the source image, not the stencil layers

- Depth-assisted background removal happens before tonal separation. Use U²-Net's confident mask
  seeds to orient relative depth on each image; refine *uncertain* edge pixels only, with a no-depth
  fallback to the original mask.
- Lambert/ambient relighting happens to the source image after masking, **before** generation of
  any stencil sheet. Lighting parameter changes must regenerate all sheets and exported geometry.
- Keep depth model weights out of the package. Pin and verify the on-demand download on Android;
  let the PWA cache the requested model. No user should need depth to use basic stencil tools.
- Never claim a model is benchmarked because a build compiled. Measure runtime and visual quality
  on representative devices before checking off performance tasks.

## 2026-11-24 — Real 3D extrusion is the approved lighting architecture

- The depth model shapes the front of a closed relief solid; the subject mask defines its silhouette and inferred walls/back. Render lights and occlusion in a real 3D scene, then pass the rendered image to the existing separator.
- Keep the source-aligned output camera independent of the interactive orbit view. Do not label image-space height-field shading as 3D scene lighting or infer unseen geometry as fact.
- Preserve the no-depth path, source alpha/framing, generation guards and the single completed separation consumed by exports. Mesh geometry resolution is not the stencil/export resolution.
- A tested mesh without an application caller is a foundation, not a completed feature. Do not retire the old renderer until the new path is integrated and verified.

## 2026-11-24 — Paint choices precede image rendering

- Offer image, subject isolation, layer count and optional arbitrary paint selection before the image/3D choice. Never require a completed separation just to choose paints.
- Colour matching is the initial mode. Tonal separation is a brightness-to-paint mapping, not a restriction to black, white or grey; arbitrary chosen RGB is valid.
- Keep a locked palette independent of scene lighting, source texture, material and adjustments. A count edit retains the selected slots that fit, not an automatic reset. All outputs use the same committed separation.

## 2026-11-24 — Subject correction and the two meanings of choosing paint

- Image first, then isolate/correct the subject, then select layer count and the palette policy before choosing image or extrusion.
- Early chosen colours constrain separation. Deferred colours are assignments to already-generated cut sheets; never rerun the separator or reorder geometry for a late assignment.
- Manual foreground restores original alpha; manual background remains excluded from every sheet even when automatic silhouette closing fills holes. An explicitly enabled opaque receiver is a new background, not a failed mask.
- Preserve source, generation guards, cut masks, paint order and export snapshot identity.

## 2026-11-24 — Loading advice belongs to one visible session

- Advance the tip only when the loading overlay opens, never on a timer. The same tip remains while work restarts and after work ends.
- Keep the overlay modal during processing. After the current generation finishes, replace progress with Continue; the button or a tap anywhere dismisses the overlay. A stale completion must not unlock current work.

## 2026-11-22 — Raster output must stay identical to the stencil preview

- Encode per-sheet PNG pixels through `Separation.render(sheet)` and composite through
  `Separation.render()`. No second separation path, no opaque white backdrop, no invented
  greyscale palette. Transparent gaps stay transparent; sheets retain their ARGB alpha.
- Export only completed separations. Debounced parameter edits invalidate pending exports;
  the Android document chooser must hold the save lock until its result callback.
- Use the existing Android/Skia PNG encoders and ZIP writer rather than duplicating codecs.
  Bound in-memory archive size, and keep individual sheet export available when an archive
  is too large.
