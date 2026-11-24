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
  each sheet. **Before bridging**, it removes a stock island from an earlier cut when
  every pixel of that island is already covered by a later cut. Black can thus be
  sprayed under a light detail reconstructed wholly in white or jointly in grey
  and white, without creating a narrow support bridge. This is an exact
  paint-order reconstruction, not inferred new artwork. For islands that cannot
  be completely restored by existing later cuts, it may carve a negative stroke
  to border-connected stock **only when later paint hides its entire footprint**.
  A source-guided fallback can add up to 64 small missing paint pixels per
  island to existing later layers, choosing only opaque paint closely matching
  the processed source and improving the original pixel match. The change is
  atomic: it is rejected if any existing border-supported retained stock in a
  later sheet becomes detached. At most sixteen costly geometry validations (successful or rejected) are
  attempted per image; unsafe corrections remain warnings. Unlike existing-cut reconstruction, this can intentionally
  change the composite at the newly painted pixels; those changes are constrained
  and reported in the app for inspection. Dark details, transparent pixels and
  ambiguous colours are not automatically repainted.
  Unrepairable islands remain annotated by existing PDF tips. Bridge paths use
  short connections with luminance-flow tie-breaking, not full semantic stroke
  reconstruction.
- The optional **Brush strokes** mode now uses `StrokeStyler` to **construct
  additive paint ribbons** inside broad grey/white or other later-layer openings.
  It does not merely make decorative notches in existing blobs. The smallest
  configured brush width is used for the centre paths, with tapered starts and
  turns guided by smoothed source luminance contours. When the photograph is flat,
  ribbons follow the shape's distance-contour field. Broad connected paint
  components are remade from these small strokes; thin and small components,
  including distinctive eye and lens marks, remain unchanged. Attached thin
  extensions are protected by local width checks on horizontal, vertical and
  diagonal axes, rather than by component size alone. Protected detail is
  **excluded** from the reconstruction quota so a long narrow line cannot
  substitute for painting its attached broad region. Ribbons never paint
  outside their original coverage, and a maximum loss budget prevents excessive
  missing paint. Some of that allowance is reserved for narrow **negative
  brushstrokes** starting only at **border-connected retained material**.
  They follow the same local form and tonal direction, providing physical
  connections and leaving safe, intentional gaps between positive paint
  ribbons. Protected thin features remain painted throughout, including where
  a negative stroke turns diagonally. Newly removed paint that cannot connect
  to the sheet border is painted back in, even when it touches a pre-existing
  unsupported island, so the island is never enlarged.
  The black base remains unchanged. This is a procedural stroke treatment,
  **not** semantic recognition or proof that it matches the supplied reference
  artwork. Classic mode restores the original cut geometry.
- `SoftGradientZones.locate` inspects gradual source-luminance transitions beside
  actual cut edges. It rejects sharp steps and regions with insufficient source
  alpha. One representative zone per local cell becomes a `SOFT GRADIENT` tip.
  Contextual PDF notes identify the edge and direction to place a small shim,
  instruct light controlled spraying, and call for a scrap test. Painting tips
  are printed only on surviving stock; cutting tips prefer cutaway material,
  falling back to safe surviving stock if the opening is too small. No note
  crosses a cut; tips without a safe position appear beside a cropped local
  detail on a non-cutting reference page.
- For performance, independent-mask island repair and gradient analysis are
  currently limited to stacks of at most sixteen sheets. The app warns when it
  skips this analysis. Existing cutting hazard tips still run for any sheet count.
- Synthetic JVM/Wasm tests cover layer overlap, exact reconstruction through
  white or grey/white, source-matched later-layer corrections, the refusal of
  dark-detail replacement and new unsupported stock, unchanged composites
  when no new paint is required, hidden bridges, and gradient detection.

## Still required

- More complete stroke synthesis: semantic grouping of strokes by object,
  perspective and anatomy; multi-stroke scribbles whose tapered ends describe
  broad gradients; and physically calibrated brush widths. The new additive
  ribbon generator is source-gradient-guided but still procedural, not semantic.
- Full stroke-trajectory synthesis for corrections across layers: the new
  source-matched paint marks address only small missing regions. Their paths
  are not yet composed as long flowing strokes. Ambiguous or unsupported
  repairs remain unresolved; do not claim the solver rebuilds every feature.
- Confidence/saliency checks around pupils, letters, hands and silhouettes.
- Scale-aware material width, real cutter tests and wall tests of shim size,
  spray pressure, distance and the direction of overspray.
- Independent visual comparison on supplied layered reference artwork. Until this
  is verified, the app must not promise it has matched any particular artist's
  brushwork.

A gradient annotation is a **suggestion to deliberately soften** an indicated cut
edge, never a promise that uncontrolled overspray will improve an image. The painter
should keep neighboring crisp boundaries sealed and test the spray technique first.
