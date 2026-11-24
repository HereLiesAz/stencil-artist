# Depth extrusion and relighting: design discussion

Status: **approved architecture (2026-11-24); implementation in progress.** The user explicitly requires the depth model to produce a 3D extrusion, lights placed in a 3D scene, and the resulting rendered image to generate the stencil. The current shading tool remains available until the new path is integrated and verified.

## Accepted architecture

**Source image → subject mask + estimated depth → closed textured relief mesh → 3D scene with lights → frontal rendered RGBA image → existing tonal/colour separation → stencil sheets and exports.**

The depth model shapes the visible front surface. The mask defines the silhouette, including holes and disconnected foreground regions. Extrude the silhouette backward into real thickness, close the side walls and back cap, and map the original photograph to the front. The sides and back are inferred geometry, not reconstruction of unseen anatomy.

Use scene-space coordinates (+X right, +Y up, +Z toward the source camera) with the longest image edge equal to one scene unit. Model depth is relative, never calibrated millimetres. Keep geometry resolution independent from texture and final image resolution. Preserve aspect ratio and an orthographic source-aligned final camera; the interactive camera may orbit, pan and zoom without changing the final stencil framing unless explicitly changed.

Scene lighting must illuminate the geometry and support actual occlusion, self-shadowing and cast shadows. Directional, point and spot lights, key/fill/rim arrangements, independent light placement, intensity, colour, softness and ambient fill are part of the plan. The optional ground/background receiver is a separate scene object, not an automatic opaque background. Moving lights must change the rendered source before separation; no layer may be relit independently.

Retain the unmodified source and original-lighting option. The rendered image becomes the authoritative prepared image for separation, preview, print and cutter output. On-demand depth-model loading and the no-depth workflow remain available. The initial textured-material render may retain baked-in source lighting; replacement or removal of those shadows is a separate explicit material choice, not a claim of intrinsic albedo recovery.

### Implementation sequence

1. **Extrusion MVP:** generate a bounded closed indexed mesh from a normalized depth field and foreground mask, with outward triangle winding, UVs and distinct front/wall/back surfaces. Add a real scene renderer, an orbitable geometry preview, one directional light and ambient fill, and a source-aligned render target feeding the existing separator. Integrate only when actual geometry occlusion and alpha/framing tests pass.
2. **Lighting:** place multiple directional/point/spot lights using scene handles; intensity, colour, distance, cone and softness controls as applicable; self/cast-shadow toggles, key/fill/rim presets, optional receiver and shadow-only/combined views.
3. **Geometry:** thickness, depth exaggeration, smoothing, flatten/clamp, near/far reversal and remapping, depth-painting corrections, topology-preserving mask handling. Support deliberate front texture/material choices and avoid claiming unseen reconstruction.
4. **Stencil feedback:** compare original and scene render; preview separation-boundary changes, unsupported stock islands and cut risks before committing the final rendered image. Preserve the established separator/export contract and benchmark representative Android/PWA workloads.

### First implementation increment

`core/ReliefMesh.kt` (under the core package source directory) introduces the common Kotlin geometry builder. It uses occupied mask cells, shared front/back vertex pairs, depth averaged only from incident foreground cells, outward-wound indexed triangles, and walls along exposed edges. Diagonal-only contacts use distinct vertices rather than a shared non-manifold edge. Texture coordinates retain top-left image convention. The builder accepts explicitly sampled normalized depth and mask grids up to 256 cells per edge, rejecting invalid inputs; it does not silently resample the source or impose that limit on exports. This is an initial bounded geometry algorithm, not a production rendering resolution guarantee.

`ReliefMeshTest` covers analytic box volume, closed oriented edges, aspect ratio, UVs, depth direction, flattening, invalid/empty input, holes and disconnected subjects; it exhausts all 512 binary 3×3 silhouettes. The mesh is not yet called by the app: no 3D preview, scene lighting, renderer or export path has been switched in this increment. The next work is the mesh-to-scene renderer and its source-aligned integration. Android/Wasm build and device performance remain to be verified.

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

## Historical alternatives considered before approval

**A. Height-field relief**: every pixel becomes a point on a textured surface, displaced along image-normal depth. Can respond smoothly to directional light and support a limited screen-space self-shadow ray march. Cannot reveal correct geometry behind silhouettes. Cheapest and closest to today's behavior.

**B. Extruded silhouette**: cut out the subject and give its silhouette an edge/thickness. This produces an explicit rim and a shadow behind it, but does *not* reconstruct its internal facial or clothing volumes. Appropriate if the goal is a sculptural cut-paper look.

**C. Layered depth cards**: split foreground elements into a small number of editable planes at depths inferred from the depth map. Light can create inter-layer cast shadows and controllable separation of overlapping shapes. Requires masks or a manual depth editor; errors can be visually severe.

**D. Reconstructed mesh**: seek actual surface geometry and missing regions. A single photo cannot reliably specify unseen sides. This is a separate, significantly more expensive feature, not a parameter change to the current implementation.

The earlier A+C proposal is superseded by the approved closed, depth-displaced front with extruded silhouette walls and back cap. A flat shading-only approximation or independent depth cards are not substitutes for the approved solid.

## Algorithm and quality requirements if redesigned

- Separate intrinsic colour from lighting so hard shadow paint does not double-darken existing source shadows; specify how to preserve or deliberately replace baked-in highlights and shadows.
- Smooth or regularize depth before estimating normals; clamp implausible discontinuities, but retain intentional silhouette and facial detail. Never infer geometry beyond a masked edge without explicit user intent.
- Define image-plane sign conventions and depth orientation consistently; allow correcting reversed near/far. Surface shading, self-shadow and silhouette shadow must agree on the direction of light.
- Generate **shadow-only and combined previews**. Provide independent cast-shadow reach, softness and opacity, and avoid casting onto alpha-transparent pixels unless the artist opts to retain a background plane.
- Keep low-resolution live previews interactive; render a full-resolution result when the user settles changes. Stencil/export work must use the same committed pixels as the preview.
- Test determinism, alpha preservation, directional light reversal, depth flattening, non-square images, constant-depth images, edge halos, slender structures, and exact alignment of pre- and post-light stencil boundaries.
- Benchmark Android and Wasm on real phone-sized photographs before making performance promises. Preserve a fully usable no-depth path.

## Remaining interaction and material choices

1. Real subject relief and actual cast/self-shadowing are approved; exact shadow quality and controls remain implementation work.
2. Editable depth and independently placeable scene lights are approved in the implementation sequence.
3. Existing photographic shadows: retained by a simple textured first render; explicit replacement/mixing and albedo estimation remain material-design decisions.
4. Shadows within the subject and an optional receiver are planned; whether a receiver shadow becomes a separate printable layer is not yet decided.

The existing `DepthLighting.relight` path remains active until the geometry renderer is integrated. The separator and export contract do not change.
