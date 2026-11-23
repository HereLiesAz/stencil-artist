# Stroke-aware stencil planning

This is a design note and a record of what the first implementation does. It is **not** a
claim that the app already reconstructs hand-painted brushstrokes or can repair every island.

## Reference: black, grey, white

The intended paint order for a dark three-tone piece is black silhouette, grey modeling,
white highlights. Black details such as pupils and nostrils may need to remain visible
through grey paint. If a dark detail is surrounded by a grey opening, the grey stencil
has an island of retained stock. A negative brushstroke bridge can keep that stock
connected; a later white reflection can conceal its visible trace, as in the glasses
example. The circle may be optically continuous even though the grey cut is not.

Other cases require **reconstruction**: remove an island in the earlier sheet and
repaint the feature using a later sheet. If no later sheet can reproduce the original
detail, omitting it is not a valid automatic fix. The finished artwork and the
step-by-step stencil plan must agree.

## Path field

Given processed-source luminance L(x,y), the preferred direction along a tonal contour
is proportional to (-dL/dy, dL/dx). Low-gradient areas should use object form, stroke
continuity and neighboring evidence, rather than an unstable normalized gradient.
A path integrator can blend its last direction with this tangent:

    vNext = normalize((1 - lambda) * vPrevious + lambda * tangent)
    pNext = p + step * vNext

The eventual stroke generator should use the **smallest viable mark width** required
for the finest meaningful detail, and cover broad regions with neighboring marks rather
than indiscriminately increasing brush size. Structural negative strokes must respect
the physical minimum retained-stock width when printed or sent to a cutter. A
pixel-width connection does not prove a millimetre-width connection.

## Implemented now

- `Separation.fromMasks` represents separately cut, independently overpainted sheet
  openings. The legacy index-based nested masks still work. All consumers use
  `mask`, `covers`, and `render`; the finished preview is the last paint opening to
  cover each pixel.
- `IslandPlanner.repair` examines retained material's four-connected components on
  each sheet. It may carve a short negative stroke from an island to border-connected
  stock **only when later paint hides its entire footprint**, checking the composite
  exactly afterward. Exposed bridge routes remain unresolved and are annotated by
  the existing PDF island tips. The current path follows short connections with
  luminance-flow tie-breaking, not full semantic stroke reconstruction.
- The optional **Brush strokes** mode now uses `StrokeStyler` to cut narrow,
  contour-guided **negative tongues** into broad grey/white or other upper-sheet
  paint openings. Every tongue starts at border-connected stock, so it is itself
  a supported material connection. Tiny marks are left unmodified, the base
  remains unchanged, and the changed area is bounded. Direction blends inward
  geometry with the source's luminance-contour tangent. This is an early
  textured-stroke treatment, **not** a full semantic reconstruction or proof
  that the exact reference brushwork has been reproduced. Classic mode restores
  the former cuts immediately.
- `SoftGradientZones.locate` inspects gradual source-luminance transitions beside
  actual cut edges. It rejects sharp steps and regions with insufficient source
  alpha. One representative zone per local cell becomes a `SOFT GRADIENT` tip.
  Contextual PDF notes identify the edge and direction to place a small shim,
  instruct light controlled spraying, and call for a scrap test. Notes never
  cross cutting lines; they move to adjacent reference pages if necessary.
- For performance, independent-mask island repair and gradient analysis are
  currently limited to stacks of at most sixteen sheets. The app warns when it
  skips this analysis. Existing cutting hazard tips still run for any sheet count.
- Synthetic JVM/Wasm tests cover layer overlap, unchanged composite paint, hidden
  bridge connectivity, unresolved exposed islands, and slow versus sharp gradients.

## Still required

- More complete stroke-based grey/white synthesis: explicit additive ribbons,
  form-aware curved paths, tapered end-of-scribble hatching, and learned stroke
  grouping. Today's supported negative tongues only articulate existing broad
  paint masses; they do not yet replace all blob-based region generation.
- Joint across-layer design for cases where **no existing later cut** conceals a
  bridge, including explicit grey/white reconstruction and black-base repairs.
- Confidence/saliency checks around pupils, letters, hands and silhouettes.
- Scale-aware material width, real cutter tests and wall tests of shim size,
  spray pressure, distance and the direction of overspray.
- Independent visual comparison on supplied layered reference artwork. Until this
  is verified, the app must not promise it has matched any particular artist's
  brushwork.

A gradient annotation is a **suggestion to deliberately soften** an indicated cut
edge, never a promise that uncontrolled overspray will improve an image. The painter
should keep neighboring crisp boundaries sealed and test the spray technique first.
