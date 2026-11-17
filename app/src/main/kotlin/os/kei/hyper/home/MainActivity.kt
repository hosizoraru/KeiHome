package os.kei.hyper.home

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import os.kei.hyper.home.data.OnboardingPreferences
import os.kei.hyper.home.data.ReleaseNotesPreferences
import os.kei.hyper.home.ui.theme.KeiHomeTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val palette = try {
      android.provider.Settings.System.getLong(contentResolver,
        os.kei.hyper.home.iconpack.NativePaletteIcon.STATE_KEY, 0L)
    } catch (_: RuntimeException) { 0L }
    // Match Shell's starting window until Compose installs the selected page theme.
    window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(
      os.kei.hyper.home.data.MonetLaunchSurface.surfaceColor(palette, getColor(R.color.window_background))))
    enableEdgeToEdge()
    val setup = OnboardingPreferences.get(this)
    val releaseNotes = ReleaseNotesPreferences.get(this, setup.state.value == OnboardingPreferences.COMPLETE)
    setContent {
      KeiHomeTheme {
        MainNavigation(setup, releaseNotes)
      }
    }
  }
}
