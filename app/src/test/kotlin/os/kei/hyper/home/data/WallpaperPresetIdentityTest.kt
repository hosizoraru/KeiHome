package os.kei.hyper.home.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WallpaperPresetIdentityTest {
  @Test fun survivesSystemArchivingAndSubpixelCropDrift() {
    val live = preset("current", "-0.00024414062", 5)
    val archived = preset("history/1795443527960", "-0.00036621094", 5)
    assertEquals(key(live), key(archived))
  }

  @Test fun distinguishesReusedSlotsAndDifferentPresetEdits() {
    val original = preset("history/123", "0", 5)
    assertNotEquals(key(original), WallpaperPresetIdentity.key(original, "lock", "other desktop"))
    assertNotEquals(key(original), key(preset("history/123", "0", 0)))
    assertNotEquals(key(original), key(preset("history/123", "-23", 5)))
  }

  @Test fun skipsAnAlreadyActiveOrMissingPresetAndUsesTheLatestMatchingRow() {
    val refreshed = listOf(28L to "other", 47L to "dark")
    assertNull(wallpaperPresetToApply("dark", "dark", refreshed) { it.second })
    assertNull(wallpaperPresetToApply("removed", "light", refreshed) { it.second })
    assertEquals(47L to "dark", wallpaperPresetToApply("dark", "light", refreshed) { it.second })
  }

  private fun key(config: JSONObject) = WallpaperPresetIdentity.key(config, "lock", "home")

  private fun preset(directory: String, translation: String, effect: Int): JSONObject {
    val base = "/data/user_de/0/com.miui.aod/files/templates/$directory"
    val subject = if (directory == "current") "/data/system/theme_magic/users/0/wallpaper/crop_subject"
      else "$base/crop_subject"
    return JSONObject("""
      {
        "updateTemplate": ${if (directory == "current") 1 else 2},
        "lockscreenInfo": {
          "clockInfo": { "clockEffect": $effect },
          "wallpaperInfo": {
            "source": "$base/wallpaper",
            "originResourcePath": "$base/wallpaper",
            "cropSubject": "$subject",
            "positionInfo": { "matrixArr": [0.6996839,0,-219.00089,0,0.6996839,$translation,0,0,1] }
          }
        },
        "homeInfo": {
          "wallpaperInfo": {
            "source": "$base/wallpaper_home",
            "isDepthVideo": true,
            "depthVideo": "$base/videoWallpaperHome"
          }
        }
      }
    """.trimIndent())
  }
}
