package com.hereliesaz.stencilartist.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** One palette: black, white, and the greys between. The only colour on screen is the user's. */
private val Monochrome = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    secondary = Color(0xFFBDBDBD),
    onSecondary = Color.Black,
    background = Color.Black,
    onBackground = Color(0xFFE6E6E6),
    surface = Color(0xFF0E0E0E),
    onSurface = Color(0xFFE6E6E6),
    surfaceVariant = Color(0xFF1C1C1C),
    onSurfaceVariant = Color(0xFF9A9A9A),
    outline = Color(0xFF3A3A3A),
    outlineVariant = Color(0xFF242424),
    error = Color(0xFFFFFFFF),
    onError = Color.Black,
)

@Composable
fun StencilArtistTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Monochrome, content = content)
}
