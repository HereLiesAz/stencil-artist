# Depth extrusion and relighting: design discussion

Status: **unresolved proposal**. This document records how the implementation works and the questions that must be answered before it changes.

## What the app currently does

1. `DepthEstimator.estimate` invokes Depth Anything V2 Small on the original RGB image. `DepthLighting.depth` normalizes the relative inverse depth to [0, 1] *per image* and resamples it to the source dimensions.
2. Background removal optionally uses the depth map only to refine uncertain U²-Net pixels (`DepthLighting.refineMask`). Confident foreground and background are preserved.
3. If enabled, `DepthLighting.relight` treats the depth map as a **height field**, obtains a normal from horizontal and vertical depth differences, and calculates `ambient + intensity × max(0, normal · light)`. It multiplies each original RGB channel by that illumination, preserves the original alpha, and clips values to 0…255.
4. This adjusted base image is passed to tone/colour separation. All generated stencil layers and exports are recomputed from it. The model is optional and is fetched only on demand.

This is **2.5D surface shading**, not volumetric extrusion: there is no real mesh, inferred hidden surface, occlusion between depth peaks, cast shadows, material/albedo separation, physically calibrated depth, or camera motion. Relative depth can be unreliable on hair, translucent materials, reflective objects and flat art. Normalizing each photo separately means the same depth-strength value has no consistent physical meaning across images.

## What the workflow should help an artist decide

The goal isn't photographic relighting for its own sake. It is to move an image's light/shadow boundaries to make an effective stencil while preserving recognizable geometry. The artist needs to see the cause and effect **before separation**, compare it with the untouched source, and avoid unexpected cut islands or destroyed features.

Proposed interaction:
- **Prepare:** isolate subject and optionally preview a diagnostic depth surface. Confidence/edge errors are made visible rather than silently treated as geometry.
- **Shape:** adjust relief exaggeration with a visual cross-section or 3D-tilt preview, plus optional smoothing and explicit near/far corrections. Do not represent arbitrary model scores as real-world millimetres.
- **Light:** manipulate a light handle around the subject; its planar direction, height, distance, softness and fill should be intelligible from the image, not five unexplained sliders. Offer original/relit comparison and a toggleable shadow-only diagnostic.
- **Separate:** inspect before/after sheet boundaries, and only then commit the adjusted base to layer generation. The same source-to-separation pipeline remains authoritative.

## Geometry and shadow options to decide

**A. Height-field relief**: every pixel becomes a point on a textured surface, displaced along image-normal depth. Can respond smoothly to directional light and support a limited screen-space self-shadow ray march. Cannot reveal correct geometry behind silhouettes. Cheapest and closest to today's behavior.

**B. Extruded silhouette**: cut out the subject and give its silhouette an edge/thickness. This produces an explicit rim and a shadow behind it, but does *not* reconstruct its internal facial or clothing volumes. Appropriate if the goal is a sculptural cut-paper look.

**C. Layered depth cards**: split foreground elements into a small number of editable planes at depths inferred from the depth map. Light can create inter-layer cast shadows and controllable separation of overlapping shapes. Requires masks or a manual depth editor; errors can be visually severe.

**D. Reconstructed mesh**: seek actual surface geometry and missing regions. A single photo cannot reliably specify unseen sides. This is a separate, significantly more expensive feature, not a parameter change to the current implementation.

A practical first prototype could combine A for shape shading with C for cast shadows and artist-editable overrides. That is a proposal, not an implementation decision.

## Algorithm and quality requirements if redesigned

- Separate intrinsic colour from lighting so hard shadow paint does not double-darken existing source shadows; specify how to preserve or deliberately replace baked-in highlights and shadows.
- Smooth or regularize depth before estimating normals; clamp implausible discontinuities, but retain intentional silhouette and facial detail. Never infer geometry beyond a masked edge without explicit user intent.
- Define image-plane sign conventions and depth orientation consistently; allow correcting reversed near/far. Surface shading, self-shadow and silhouette shadow must agree on the direction of light.
- Generate **shadow-only and combined previews**. Provide independent cast-shadow reach, softness and opacity, and avoid casting onto alpha-transparent pixels unless the artist opts to retain a background plane.
- Keep low-resolution live previews interactive; render a full-resolution result when the user settles changes. Stencil/export work must use the same committed pixels as the preview.
- Test determinism, alpha preservation, directional light reversal, depth flattening, non-square images, constant-depth images, edge halos, slender structures, and exact alignment of pre- and post-light stencil boundaries.
- Benchmark Android and Wasm on real phone-sized photographs before making performance promises. Preserve a fully usable no-depth path.

## Decisions for review

1. Is **extrusion** meant to create real relief on the *subject itself*, cast an offset silhouette shadow, or both?
2. Should the artist reshape the estimated depth (brush near/far, smooth/flatten), or only reposition the light?
3. Should existing photograph shadows be **replaced**, **retained**, or **mixed** with new lighting?
4. Where may shadows fall: within the isolated subject, onto a kept-background plane, or onto a separate printable stencil layer?

Until these are answered, the existing `DepthLighting.relight` and export path remain unchanged.
