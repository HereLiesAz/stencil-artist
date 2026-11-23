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
console.log("Original MTN 94 PNG, layering order and catalogue routing verified.");
