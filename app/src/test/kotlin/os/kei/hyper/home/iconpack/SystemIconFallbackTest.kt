package os.kei.hyper.home.iconpack

import org.junit.Assert.*
import org.junit.Test
import os.kei.hyper.home.data.AppPreferencesState
import os.kei.hyper.home.data.SystemIconFallback

class SystemIconFallbackTest {
  @Test fun defaultIsModernNativeAndImportIdentitiesDoNotReuseOldVariants() {
    assertEquals(SystemIconFallback.DEFAULT, AppPreferencesState().systemIconFallback)
    assertFalse(AppPreferencesState().colorOsForOtherPacks)
    val old = "coloros:classic:SYSTEM:abcdef"
    val first = SystemIconFallback.DEFAULT.sourceId(old)
    assertNotEquals(old, first)
    assertEquals(first, SystemIconFallback.DEFAULT.sourceId(first))
    assertEquals(old, SystemIconFallback.baseSourceId(SystemIconFallback.CLASSIC.sourceId(first)))
    assertNotEquals(first, SystemIconFallback.BOUNDLESS.sourceId(old))
  }

  @Test fun provenanceIsBoundedCanonicalAndRejectsTraversalDuplicatesAndUnknownModes() {
    val manifest = SystemIconFallbackManifest(SystemIconFallback.CLASSIC, setOf("com.miui.notes", "com.android.settings"))
    assertEquals(manifest, SystemIconFallbackManifest.decode(manifest.encode()))
    val names = SystemIconFallbackManifest(SystemIconFallback.DEFAULT, setOf("com.miui.securitycenter"), true)
    assertEquals(names, SystemIconFallbackManifest.decode(names.encode()))
    assertTrue(AppPreferencesState().systemAppNameMatching)
    for (text in listOf("KeiHomeSystemFallback1\nUNKNOWN\n", "KeiHomeSystemFallback1\nDEFAULT\n../foo\n",
      "KeiHomeSystemFallback1\nDEFAULT\ncom.a\ncom.a\n", "KeiHomeSystemFallback1\nDEFAULT\ncom.z\ncom.a\n")) {
      assertThrows(IllegalArgumentException::class.java) { SystemIconFallbackManifest.decode(text.toByteArray()) }
    }
  }

  @Test fun fallbackOptionsRoundTripIndependentlyWithoutChangingLegacyBytes() {
    for (names in listOf(false, true)) for (colorOs in listOf(false, true)) {
      val manifest = SystemIconFallbackManifest(SystemIconFallback.DEFAULT, setOf("com.example.app"), names, colorOs)
      assertEquals(manifest, SystemIconFallbackManifest.decode(manifest.encode()))
    }
    assertEquals("KeiHomeSystemFallback1\nDEFAULT\ncom.example.app\n",
      SystemIconFallbackManifest(SystemIconFallback.DEFAULT, setOf("com.example.app")).encode().toString(Charsets.UTF_8))
  }

  @Test fun systemAppNameKeysKeepStoreAndManagerRolesDistinct() {
    assertEquals(listOf("phonemanager", "手机管家"), SystemAppNameMatcher.keys("手机管家"))
    assertEquals(listOf("themestore", "主题商店"), SystemAppNameMatcher.keys("主题商店"))
    assertEquals("appmarket", SystemAppNameMatcher.keys("软件应用商店").first())
    assertEquals(listOf("淘宝"), SystemAppNameMatcher.keys("淘宝"))
  }

  @Test fun everyDeclaredNativeVariantProtectsArtworkEvenIfThePreviewCannotRenderIt() {
    val names = nativeFallbackProtectedNames(sequenceOf(
      "nightmode/res/drawable-anydpi-v99/com.android.settings.png",
      "layer_animating_icons/com.miui.notes/unknown/fancy/custom.xml",
      "fancy_icons/com.android.camera/unrecognized.blob",
      "animating_icons/com.miui.gallery/quiet/den480/quietImage.png",
      "com.android.documentsui.png",
      "icon_mask.png", "res/raw/com.other.xml",
    ))
    assertEquals(setOf("com.android.settings", "com.miui.notes", "com.android.camera", "com.miui.gallery",
      "com.android.documentsui"), names)
    assertFalse("com.android.settings.other" in names)
  }
}
