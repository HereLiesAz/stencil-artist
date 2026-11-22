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

## 2026-11-22 — Print guidance must not change cuts

- Contextual PDF tips belong **inside uncut, surviving stencil stock**, never in a side gutter.
- Check the entire note's physical bounding rectangle plus safety clearance against the full
  sheet's cut mask. If it cannot fit, use an adjacent detail on a non-cutting reference page.
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
