package com.hereliesaz.stencilartist.core

/** Directional lights are at infinity; point lights radiate in every direction; spots aim at a target. */
enum class SceneLightKind { DIRECTIONAL, POINT, SPOT }

/**
 * Independent scene emitter. Azimuth is clockwise from image-right, elevation is measured toward
 * the source camera (negative is behind the solid). Positional emitters orbit the scene origin at
 * [distance] image-relative units; the minimum distance keeps the whole bounded relief in front of
 * their shadow camera. [targetX], [targetY], [targetZ] are scene-space spot aim coordinates.
 * [color] is opaque RGB; [softness] is a shadow-map filter radius in texels, not physical emitter size.
 */
data class SceneLight(
    val kind: SceneLightKind = SceneLightKind.DIRECTIONAL,
    val azimuth: Float = 45f,
    val elevation: Float = 50f,
    val distance: Float = 1.5f,
    val intensity: Float = .7f,
    val color: Int = 0xFFFFFF,
    val softness: Int = 0,
    val castsShadows: Boolean = true,
    val cone: Float = 35f,
    val targetX: Float = 0f,
    val targetY: Float = 0f,
    val targetZ: Float = 0f,
) {
    init {
        require(azimuth in -180f..180f && elevation in -90f..90f)
        require(distance in 1.5f..4f && intensity in 0f..4f && color in 0..0xFFFFFF)
        require(softness in 0..4 && cone in 5f..85f)
        require(targetX in -.5f.. .5f && targetY in -.5f.. .5f && targetZ in -.5f..1f)
    }

    companion object {
        /** Bound simultaneous shadow buffers and per-pixel lighting work on mobile and Wasm. */
        const val MAX_LIGHTS = 4

        /** Starting arrangement with an independent key, fill and rear rim emitter. */
        fun studio(): List<SceneLight> = listOf(
            SceneLight(azimuth = -45f, elevation = 45f, intensity = .9f, softness = 2),
            SceneLight(azimuth = 145f, elevation = 50f, intensity = .35f, color = 0xDDE8FF, softness = 3),
            SceneLight(kind = SceneLightKind.POINT, azimuth = -90f, elevation = -20f,
                intensity = 2f, color = 0xFFE5CC, softness = 2),
        )
    }
}
