package com.hereliesaz.stencilartist.app

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import com.hereliesaz.stencilartist.app.generated.resources.Res
import com.hereliesaz.stencilartist.app.generated.resources.cavier
import com.hereliesaz.stencilartist.app.generated.resources.prodes_stencil
import org.jetbrains.compose.resources.Font

/** Private builds use the user-supplied Prodes wordmark and Cavier studio headings.
 * Controls, small labels, numerical readouts and physical export text retain their original fonts.
 */
@Composable
internal fun brandTypography(): Typography {
    val defaults = Typography()
    val prodes = FontFamily(Font(Res.font.prodes_stencil))
    val cavier = FontFamily(Font(Res.font.cavier))
    return Typography(
        displaySmall = defaults.headlineMedium.copy(fontFamily = prodes),
        headlineMedium = defaults.headlineMedium.copy(fontFamily = cavier),
    )
}
