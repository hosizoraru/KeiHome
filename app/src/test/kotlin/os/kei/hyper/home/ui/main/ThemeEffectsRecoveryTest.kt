package os.kei.hyper.home.ui.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeEffectsRecoveryTest {
  @Test
  fun unknownFailedChoiceCanExplicitlySaveOffButCannotOptIn() {
    for (state in listOf(ThemeEffectsUiState(readFailed = true), ThemeEffectsUiState(saveFailed = true))) {
      assertTrue(state.canSaveThemeEffects(false))
      assertFalse(state.canSaveThemeEffects(true))
    }
    assertFalse(ThemeEffectsUiState().canSaveThemeEffects(false))
    assertFalse(ThemeEffectsUiState().canSaveThemeEffects(true))
  }

  @Test
  fun recoveryWaitsForIoAndRemoteAvailability() {
    val failed = ThemeEffectsUiState(readFailed = true)
    assertFalse(failed.copy(saving = true).canSaveThemeEffects(false))
    assertFalse(failed.copy(loading = true).canSaveThemeEffects(false))
    assertFalse(failed.copy(unavailable = true).canSaveThemeEffects(false))
    assertTrue(failed.copy(saveFailed = true).canSaveThemeEffects(false))
  }

  @Test
  fun knownChoiceStillAllowsExplicitToggleRetry() {
    val failed = ThemeEffectsUiState(savedChoice = false, saveFailed = true)
    assertTrue(failed.canSaveThemeEffects(true))
    assertTrue(failed.canSaveThemeEffects(false))
    assertFalse(failed.copy(saving = true).canSaveThemeEffects(true))
  }
}
