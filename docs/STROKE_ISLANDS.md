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
- The optional **Brush strokes** mode now uses `StrokeStyler` to **construct
  additive paint ribbons** inside broad grey/white or other later-layer openings.
  It does not merely make decorative notches in existing blobs. The smallest
  configured brush width is used for the centre paths, with tapered starts and
  turns guided by smoothed source luminance contours. When the photograph is flat,
  ribbons follow the shape's distance-contour field. Broad connected paint
  components are remade from these small strokes; thin and small components,
  including distinctive eye and lens marks, remain unchanged. Ribbons never paint
  outside their original coverage, and a maximum loss budget prevents excessive
  missing paint. Any **new enclosed stock gap** composed solely of previously
  painted pixels is painted back in to avoid newly introduced stencil islands.
  The black base remains unchanged. This is a procedural stroke treatment,
  **not** semantic recognition or proof that it matches the supplied reference
  artwork. Classic mode restores the original cut geometry.
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

- More complete stroke synthesis: semantic grouping of strokes by object,
  perspective and anatomy; multi-stroke scribbles whose tapered ends describe
  broad gradients; and physically calibrated brush widths. The new additive
  ribbon generator is source-gradient-guided but still procedural, not semantic.
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
