# Material evaluation

The purpose is to compare **material estimates**, not to score how attractive a newly lit image looks. No unique reflectance can be recovered from an arbitrary single photo. The controlled case has hand-authored reference colours and shaded observations, including separate cast-shadow and specular regions. It checks the production compensation path against source skin and the default white solid coat. It does not prove performance on photographs.

## Run

CI uploads `material-evaluation` after the JVM tests. Locally:

```sh
STENCIL_MATERIAL_OUTPUT="$PWD/build/material-evaluation" ./gradlew :core:jvmTest --tests '*MaterialEvaluationTest' --rerun-tasks
```

To evaluate actual photos, additionally set `STENCIL_MATERIAL_CASES` to an absolute directory of case subdirectories. Each case contains:

- `source.png`: aligned working-size photograph, with the chosen subject alpha.
- `reference.png`: registered reference reflectance, in the same RGB encoding and exposure convention (e.g. calibrated diffuse capture of the same subject). Record how it was obtained.
- `depth.png`: aligned unsigned 16-bit grayscale relative inverse depth from the app's depth estimator or a separately documented measurement. Keep the same depth for all candidates. Do not silently substitute luminance for depth.
- `regions.png`: grayscale sample IDs, 0 excluded, 1 diffuse, 2 cast shadow, 3 specular highlight. Annotate independently of candidate output. At least one opaque diffuse region is mandatory; include the other two where present.
- `case.properties`: amount, azimuth, elevation, ambient, depthStrength, reverseDepth, solidRgb. For example: `amount=1`, `azimuth=0`, `elevation=45`, `ambient=0.25`, `depthStrength=3`, `reverseDepth=false`, `solidRgb=FFFFFF` (one per line). Lock these before reviewing scores.
- Optional `candidate.png`: output of a proposed intrinsic estimator, at the same size and alpha as the source. Record model/version, preprocessing, color space, license and inference hardware separately.

Use permitted images and do not commit private photographs. A suitable starting research dataset is Grosse et al., *Ground truth dataset and baseline evaluations for intrinsic image algorithms* (ICCV 2009), https://dspace.mit.edu/entities/publication/be74fe4c-85d5-4421-ba93-11b506d7ffb4 ; check its image usage terms and obtain aligned depth separately. The original dataset alone does not provide this app's inferred depth. Alternatively photograph matte painted material under a diffuse reference and then directional light, keeping camera, subject, exposure and white balance fixed. Include a cast shadow and a glossy object in separate trials.

## Interpret

The CSV records absolute RGB MAE and an exposure-aligned RGB RMSE per labeled region, in encoded 8-bit channel levels. Exposure alignment is one least-squares multiplicative gain fitted on diffuse samples and reused for all regions; it is not a per-region adjustment. No output clipping is applied to the aligned error calculation. The two metrics distinguish actual colour/exposure error from relative differences. This is not a standardized intrinsic-image leaderboard metric or a calibrated perceptual score. Compare outputs visually for hue, retained dark details, shadow leakage, clipped highlights, and texture; a single numerical improvement is not acceptance.

The solid baseline uses the selected constant RGB, not oracle per-pixel reflectance. Use the same declared solid colour for all comparisons of a case. Test no-depth/solid behavior separately. Candidate output must have identical dimensions and alpha. Real case execution is optional and local; CI only exercises controlled artwork until suitable real inputs and depth are supplied. Keep the actual-photo acceptance gate open.
