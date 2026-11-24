# Depth extrusion and relighting: design discussion

Status: **approved architecture (2026-11-24); implementation in progress.** The user explicitly requires the depth model to produce a 3D extrusion, lights placed in a 3D scene, and the resulting rendered image to generate the stencil. The 3D MVP is now connected to the opt-in Light stage; the original-lighting path remains available.

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

### Second implementation increment: shared scene renderer

`ReliefScene.prepare` conservatively samples the masked source into a mesh grid (longest edge at most 128 cells); visible pixels contribute depth, transparent pixels do not. The resulting indexed solid is rasterized with actual triangle visibility and a 512-square directional-light shadow depth buffer. The source-aligned orthographic render retains original working dimensions and exact source alpha; thin source features are not discarded. Coarse geometry can still approximate silhouette and shadow edges. The orbit preview is a separate 256-square render, including neutral inferred walls and cap. Front-face lighting currently uses flat triangle normals, textured RGB with existing photographic light, ambient fill and hard geometric shadows. No background receiver, point/spot lights, soft shadows, GPU acceleration or recovered albedo yet.

`StencilState.remake` calls the scene renderer before either separator, using the exact returned RGBA image for every downstream path. It clears the scene and separation synchronously on changes and guards scene publication by generation. An unavailable depth model or renderer falls back explicitly to the original lighting. Orbiting changes neither the source camera nor the separation. The Light stage exposes azimuth/elevation, intensity, ambient, relief, thickness, depth reversal and a shadow toggle, plus yaw/pitch inspection.

`ReliefSceneTest` verifies exact alpha/framing, a separate raised solid casting a shadow across a transparent gap onto a lower surface, light reversal, visibility of walls, orbit independence and preservation of sparse non-square source detail. Five focused JVM tests passed locally; Android/Wasm builds and visual device checks remain outstanding. The old `DepthLighting.relight` helper remains for regression coverage, not the active app rendering path.

### Third implementation increment: independently placed lights

`SceneLight` describes an independent directional, point or spot emitter. The editor supports up to four lights, adding/removing/selecting them, a key/fill/rim preset, draggable front-projection placement handles, accessible azimuth/elevation controls (front or rear hemisphere), finite position distance, RGB, intensity, spotlight half-cone/XYZ aim and individual shadow toggles. A null emitter list retains the original directional configuration; an empty list gives ambient alone. Positions are on a bounded sphere outside the relief, in relative scene units, rather than freely inside the mesh.

Directional lights use orthographic shadow projections. Finite emitters use perspective shadow projections with reciprocal camera depth interpolated over the actual mesh; their diffuse intensity falls off with squared distance. Spot emission tapers within the cone. Light contributions add by RGB channel with neutral ambient fill. A nine-tap percentage-closer shadow filter produces partial occlusion at edges (radius 0–4 texels); this is approximate shadow softness, not physical area-light transport. The combined and grayscale occlusion orbit views are diagnostics, never separator input. Only the fixed frontal combined render drives the stencil. Source alpha is unchanged, including transparent gaps. Optional receiver geometry, inside-mesh light placement, physically sized soft emitters, recovered albedo and device performance remain outstanding.

Nine focused JVM scene test methods cover the earlier source/geometry guarantees, independent coloured contributions, inverse-square point falloff, spot aiming, finite position point/spot geometric shadows and per-light toggles, filtered partial visibility and orbit diagnostic independence. Synthetic multi-light renders were visually inspected; coarse mesh and shadow-map artifacts remain. Android/Wasm build and real-device visual verification are still required.

### Fourth implementation increment: surface material choice

The Light stage exposes two mutually exclusive choices: **Image skin** uses original RGB on the visible front surface (including baked photographic shadows); inferred walls and cap retain neutral material. **Solid colour** coats every face with one user-selected RGB colour, removing original RGB variation without changing depth geometry, light positions, or the source alpha mask. White is the initial solid colour. The selected RGB is remembered while switching between modes. Both materials pass through the same geometric light/shadow computation, orbit preview and frontal stencil input. The occlusion-only diagnostic remains independent of material colour.

The shared RGB controls edit surface or emitter colour without duplicating channel editing logic. The source image is unchanged. No claim is made about recovery of intrinsic photographic albedo. Two additional JVM scene tests verify the exact solid RGB under white light, reduced intensity, partial alpha and transparent gaps, reversibility, uniform material on inferred surfaces and unchanged diagnostic output. Eleven focused standalone JVM scene tests passed, and synthetic comparisons were visually inspected; Android/Wasm and device verification remain outstanding.

### Flat relight repair

The artist reported that flat depth lighting was weak even before extrusion was added. The original code scaled a normalized full-width depth ramp to approximately 0.28 slope at strength 3 (two samples per derivative, 24/518 multiplier); a 50-degree light and ambient 0.55 further suppressed variation. Multiplying the existing RGB could never recover detail in black pixels, and sampling across transparent silhouette edges could invent normals. The later scene integration also removed the flat mode from the app call path.

The Light stage now independently selects Original, Depth relight, or 3D scene, retaining settings for both lighting modes. Flat mode uses a foreground 2nd–98th percentile depth range with a 0.15 minimum, actual one-/two-sided sample distances, image-relative height scaling (1.05 nominal units at strength 3), and no derivatives across transparent pixels. Default flat elevation is 35 degrees, ambient 0.18, intensity 1 and matte mix 65%; the 3D defaults are unchanged. Matte mix is deliberate white material replacement, not intrinsic albedo recovery. A zero strength bypasses flat relighting. Flat mode computes surface shading only, not geometric cast shadows. The source-aligned output of either mode is the input to the same stencil separator. Original image and alpha remain unchanged. Broad-ramp contrast, silhouette sampling and matte replacement have dedicated regression fixtures. Real photograph and device validation remain necessary.

### Palette-first studio

Setup comes first: open the image, choose colour matching (default) or tonal bands, request a layer count, and choose automatic colours, custom RGB, or can-based colours where applicable. Then Light selects Original, Depth relight or 3D scene. Adjust contains background isolation, source/skin and prepared-image effects. The first stage can edit and lock a palette before any image or separation exists. A locked palette survives lighting, material and adjustment changes; count changes retain the first requested slots and add distinct editable suggestions when growing. Automatic suggestions may change with the rendered image. In colour mode the separator orders the selected RGB paints independently of their input-slot positions; tonal mode uses the chosen paints in paint order for brightness bands, not necessarily greys. The palette constrains separation, not the scene material or lights. Paint catalogue matching and selected cans retain their existing semantics. All exports still consume the completed Separation. Palette resize and colour-pipeline regression cases cover arbitrary RGB sets, but Android/Wasm and real-device validation remain open.

### Non-destructive image preparation

The Adjust stage exposes independent source/skin and prepared-image adjustment stacks with separate resets. Source adjustments operate on the isolated photograph before flat relighting or texture sampling for extrusion. Model inference and masking deliberately continue to read the original photograph, avoiding reinference on every slider movement. Solid extrusion material ignores skin RGB adjustments. Prepared-image effects apply after Original, Depth relight or 3D scene rendering, before every tonal/colour separation, paint plan and island analysis. Orbit combined previews also show the prepared filters; occlusion diagnostics remain unmodified. The fixed frontal prepared image is authoritative for the stencil.

`ImageAdjustments` applies robust auto-levels and manual levels, exposure, brightness, contrast, gamma, shadow/highlight adjustment, saturation, warmth/tint, grayscale/invert, mask-aware whole-pixel despeckling, range-gated edge-preserving smoothing, alpha-weighted box blur, unsharp detail gain, local contrast and optional hue-preserving HSV-value posterization. Spatial radii are working-image pixels, not physical dimensions. Posterization controls value bands, not sheet count. No effect changes source alpha, dimensions or the original pixels. Seven standalone JVM fixtures exercise identity/invert, visible auto-level endpoints, hidden RGB exclusion, despeckle, edge preservation, posterization and combined effects. Device performance and aesthetic/physical stencil quality are not yet verified.

### Optional shadow receiver

The 3D Light stage can explicitly add a rectangular, image-sized backdrop behind the closed solid's back cap. It uses the shared scene illumination and geometric shadow maps, has its own opaque RGB material and adjustable gap, and is visible in orbit and fixed frontal views. It receives shadows but does not cast its own (rear lights remain usable on the subject). It is off by default: without it the source alpha contract is unchanged. When enabled, the backdrop deliberately fills the frontal frame and partial subject alpha composites over it; the resulting pixels enter the same adjustment/separation path. No independent printable shadow sheet is implied. Two new scene regression fixtures cover opt-in compositing, shadow direction and orbit independence; full CI and device verification remain open.

### Interactive tonal curves

Both adjustment stacks in Adjust offer a Curve tab with five fixed input knots (0, 64, 128, 192, 255) and independently editable output values. Drag vertically to change a knot; release commits one adjustment through the existing debounced state path. An accessible point selector and output slider provide the same operation. The displayed curve uses the exact 256-entry lookup table used by processing. Monotone cubic interpolation keeps outputs ordered and allows deliberate flat bands, black lift and white compression without overshoot. The curve remaps HSV value after colour adjustments and before spatial filters, scaling the RGB channels together to preserve hue, with neutral lift for black. Alpha, hidden RGB and the original source are unchanged. The source/skin and final-render curves are independent; each has its own reset. Six focused JVM tests exercise identity, exact knots, flat bands, intermediate values, constrained editing, extreme ordered knots and image/alpha preservation. Device UI and actual-photo validation remain open.

### Stencil inspection

Inspect offers a read-only diagnostic for a selected, committed cut sheet, with physical-width and minimum-stock controls shared with printing/cutting. `StencilInspection.inspect` reads `Separation.mask`, including any independent repairs, and reuses `StencilPrintTips.detect` for full-sheet unsupported islands and fine openings under 1.5 mm. Cyan identifies cut boundaries on the opening side; red and yellow crosses locate actual island/fine-opening anchors; orange identifies candidate narrow stock. The latter is the union of horizontal and vertical retained runs bounded by openings at both ends and narrower than the requested physical minimum, counted in pixels rather than distinct bridges. It is a screening heuristic, not a complete diagonal clearance or strength analysis. A pathological detail count fails visibly instead of dropping findings. Inspect only computes one selected sheet, and its keyed result is discarded on a changed separation or physical setting. No diagnostic pixels enter the separator, print guidance, or exports. Real-photo evaluation and physical validation remain open.

## Previous implementation (before scene integration)

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
3. Existing photographic shadows: retain them with Image skin, or discard original RGB using Solid colour. Intrinsic albedo estimation and mixed material choices remain separate design work.
4. Shadows within the subject and an optional receiver are planned; whether a receiver shadow becomes a separate printable layer is not yet decided.

Flat depth relight and 3D scene rendering are independent opt-in lighting paths. The separator and export contract do not change.
