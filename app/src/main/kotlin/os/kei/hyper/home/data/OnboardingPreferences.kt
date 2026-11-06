package os.kei.hyper.home.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class OnboardingPreferences(
  context: Context,
  id: String = "onboarding_v1",
  existingConfiguration: Boolean = hasExistingConfiguration(context),
) {
  private val storage = AppLocalStorage.open(context, id)
  private val progress = MutableStateFlow(
    if (storage.containsKey(KEY)) storage.decodeInt(KEY, 0).takeIf { it in COMPLETE until PAGE_COUNT } ?: 0
    else (if (existingConfiguration) COMPLETE else 0).also { check(storage.encode(KEY, it)) },
  )
  val state = progress.asStateFlow()

  fun visit(page: Int) {
    require(page in 0 until PAGE_COUNT)
    if (progress.value != COMPLETE) save(page)
  }

  fun complete() = save(COMPLETE)

  private fun save(value: Int) {
    if (value == progress.value) return
    check(storage.encode(KEY, value))
    progress.value = value
  }

  companion object {
    const val PAGE_COUNT = 5
    const val COMPLETE = -1
    private const val KEY = "progress_v1"
    @Volatile private var instance: OnboardingPreferences? = null

    fun get(context: Context): OnboardingPreferences = instance ?: synchronized(this) {
      instance ?: OnboardingPreferences(context.applicationContext).also { instance = it }
    }

    // Sample before the theme opens AppPreferences: upgrades keep their existing entry point.
    private fun hasExistingConfiguration(context: Context): Boolean =
      File(context.filesDir, "mmkv/app_display_v1").isFile ||
        File(context.applicationInfo.dataDir, "shared_prefs/app_display.xml").isFile
  }
}
