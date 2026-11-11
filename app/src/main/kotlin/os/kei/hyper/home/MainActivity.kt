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
