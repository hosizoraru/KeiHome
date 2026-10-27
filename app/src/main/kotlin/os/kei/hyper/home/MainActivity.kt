package os.kei.hyper.home

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import os.kei.hyper.home.ui.theme.KeiHomeTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    enableEdgeToEdge()
    setContent {
      KeiHomeTheme {
        MainNavigation()
      }
    }
  }
}
