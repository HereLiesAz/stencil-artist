// Verifies that the original user-supplied MTN 94 image remains byte-for-byte intact.
// Run with: node tools/tests/mtn94-original-packshot.mjs
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";

const source = readFileSync("composeApp/src/commonMain/composeResources/drawable/mtn94_original_packshot.png");
const expectedSha256 = "5e4c3b8bacbe4d2e4f28af3be29fa1b58971e447517dccdd34020948419dabe4";
assert.equal(
  createHash("sha256").update(source).digest("hex"),
  expectedSha256,
  "The user-provided image must not be cut, recoloured, resized, or recompressed.",
);
assert.equal(source.subarray(0, 8).toString("hex"), "89504e470d0a1a0a", "original PNG signature");
assert.equal(source.readUInt32BE(16), 1122, "original image width");
assert.equal(source.readUInt32BE(20), 1402, "original image height");
assert.equal(source[25], 6, "the original has an RGBA alpha channel");

// The second user asset is a five-top sprite strip. Its source bytes must remain intact.
const top = readFileSync("composeApp/src/commonMain/composeResources/drawable/mtn94_original_top_strip.png");
assert.equal(createHash("sha256").update(top).digest("hex"),
  "482bfef5cc5ebdfb7c81f3da9eb5d3be1228ec6ba8e4ef0293746dfb11e2cf42",
  "Do not change or recreate the user-provided spray-can tops");
assert.equal(top.subarray(0, 8).toString("hex"), "89504e470d0a1a0a");
assert.equal(top.readUInt32BE(16), 724);
assert.equal(top.readUInt32BE(20), 2172);
assert.equal(top[25], 6, "the five-top strip has an alpha channel");

const artwork = readFileSync("composeApp/src/commonMain/kotlin/com/hereliesaz/stencilartist/app/CanArt.kt", "utf8");
const page = readFileSync("composeApp/src/commonMain/kotlin/com/hereliesaz/stencilartist/app/PaintPanel.kt", "utf8");
assert.match(artwork, /painterResource\(Res\.drawable\.mtn94_original_packshot\)/);
assert.match(artwork, /\.background\(Color\.White\)/);
assert.match(artwork, /drawPath\(shoulder, Color\(colour\)\)/);
assert.ok(
  artwork.indexOf("drawPath(shoulder, Color(colour))") < artwork.indexOf("painterResource(Res.drawable.mtn94_original_packshot)"),
  "paint is rendered behind the original PNG, not painted over it",
);
assert.match(page, /can\.brand == "MTN \(Montana Colors\)" && can\.line == "94"/);
assert.match(page, /Mtn94Packshot\(can\.rgb, 44\.dp, 120\.dp, canImage\)/);
assert.match(page, /CanSide\(can\.rgb, 44\.dp, 120\.dp, canImage\)/);
assert.match(page, /\}\.filter \{ it\.rgb != null \}/,
  "Only resolved colours belong in the rack results");
assert.match(page, /open\.cans\.mapNotNull \{ can ->/,
  "The open carousel must refresh and exclude unresolved entries");
assert.match(page, /chosen\.map \{ it\.layer\.primary \}\.filter \{ it\.rgb != null \}/,
  "The chosen-can carousel must also exclude unresolved entries");
assert.match(artwork, /imageResource\(Res\.drawable\.mtn94_original_top_strip\)/);
assert.match(artwork, /drawCircle\(Color\(colour\), diameter \* 0\.45f, center\)/);
assert.match(artwork, /srcOffset = IntOffset\(140, frameTop\)/);
assert.match(artwork, /srcSize = IntSize\(440, frameBottom - frameTop\)/);
// Independent scan of the original PNG's alpha rows locates seam minima here.
// All frames together account for the complete five tops with no overlaps or gaps.
const frameEdges = [0, 428, 847, 1264, 1678, 2111];
for (const edge of frameEdges) {
  assert.ok(artwork.includes("-> " + edge),
    "The original frame bounds must include source row " + edge);
}
for (let i = 1; i < frameEdges.length; i++) {
  assert.ok(frameEdges[i] - frameEdges[i - 1] >= 400,
    "Each crop must accommodate a complete metal top");
}
assert.ok(frameEdges[frameEdges.length - 1] <= top.readUInt32BE(20));
assert.ok(
  artwork.indexOf("drawCircle(Color(colour), diameter * 0.45f, center)") <
  artwork.indexOf("drawImage(\n            image = original,"),
  "Each can's solid colour circle must render behind the supplied top-view art");
assert.match(page, /\.height\(320\.dp\)\.background\(Color\.White\)/,
  "One white layer backs the entire rack, not one white circle per can");

console.log("Original MTN 94 PNG, layering order and catalogue routing verified.");
