package com.hereliesaz.stencilartist.app

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable

/** Public builds retain the standard UI typeface without bundling private font assets. */
@Composable
internal fun brandTypography(): Typography {
    val defaults = Typography()
    return Typography(displaySmall = defaults.headlineMedium)
}
