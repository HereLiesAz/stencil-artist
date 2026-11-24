package com.hereliesaz.stencilartist.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Charcoal, paper and weathered teal sampled from the launcher artwork.
 * Stencil and paint previews retain their independent neutral backgrounds and exact paint colours.
 */
private val StudioPalette = darkColorScheme(
    primary = Color(0xFF97C4C9),
    onPrimary = Color(0xFF132A2E),
    primaryContainer = Color(0xFF416770),
    onPrimaryContainer = Color(0xFFEFEBE5),
    secondary = Color(0xFFBAC8C8),
    onSecondary = Color(0xFF202728),
    secondaryContainer = Color(0xFF344446),
    onSecondaryContainer = Color(0xFFEFEBE5),
    tertiary = Color(0xFFEFEBE5),
    onTertiary = Color(0xFF151B1D),
    tertiaryContainer = Color(0xFF414645),
    onTertiaryContainer = Color(0xFFEFEBE5),
    background = Color(0xFF151B1D),
    onBackground = Color(0xFFEFEBE5),
    surface = Color(0xFF202728),
    onSurface = Color(0xFFEFEBE5),
    surfaceVariant = Color(0xFF283233),
    onSurfaceVariant = Color(0xFFBAC8C8),
    surfaceTint = Color(0xFF97C4C9),
    surfaceContainerLowest = Color(0xFF101618),
    surfaceContainerLow = Color(0xFF1A2022),
    surfaceContainer = Color(0xFF202728),
    surfaceContainerHigh = Color(0xFF283233),
    surfaceContainerHighest = Color(0xFF344446),
    inverseSurface = Color(0xFFEFEBE5),
    inverseOnSurface = Color(0xFF151B1D),
    inversePrimary = Color(0xFF416770),
    outline = Color(0xFF71888B),
    outlineVariant = Color(0xFF495C60),
    error = Color(0xFFFFB4A7),
    onError = Color(0xFF421B17),
    errorContainer = Color(0xFF66312B),
    onErrorContainer = Color(0xFFFFDAD4),
)

/** Shared Android and web chrome. The private font option never changes artwork or export text. */
@Composable
fun StencilArtistTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = StudioPalette, typography = brandTypography(), content = content)
}
