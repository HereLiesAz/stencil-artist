package com.hereliesaz.stencil_artist

import android.os.Bundle
import com.hereliesaz.aznavrail.updates.AzUpdateChecker
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.hereliesaz.stencilartist.app.App

/** The Android entry point. Everything on screen is [App], shared with the web build. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { App() }
        // Play-installed releases receive updates through Google Play, not GitHub APKs.
        if (!BuildConfig.PLAY_RELEASE) AzUpdateChecker.check(this, "HereLiesAz/stencil-artist")
    }
}
