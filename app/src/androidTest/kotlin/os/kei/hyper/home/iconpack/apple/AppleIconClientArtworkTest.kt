package os.kei.hyper.home.iconpack.apple

import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleIconClientArtworkTest {
  private val files = AppleAppMatch("1232058109", "Files", "Apple", "com.apple.DocumentsApp")
  private val options = AppleIconOptions()

  // Apple's US Files catalog response, captured 2026-11-08.
  private val extended = "https://is1-ssl.mzstatic.com/image/thumb/PurpleSource221/v4/" +
    "46/b1/5a/46b15a95-7ea6-d913-261e-576c0f2e0e3a/Placeholder.mill/{w}x{h}{c}.{f}"
  private val raster = "https://is1-ssl.mzstatic.com/image/thumb/Purple221/v4/" +
    "35/df/6a/35df6a7a-b5e0-9221-4a90-1dee835fe6ca/" +
    "filesCalistoga-0-0-1x_U007epad-0-1-0-85-220.png/{w}x{h}{c}.{f}"

  private fun catalog(includeExtended: Boolean): ByteArray {
    val platform = JSONObject().put("artwork", JSONObject().put("url", raster))
    if (includeExtended) platform.put("iconArtwork", JSONObject().put("url", extended))
    return JSONObject().put("data", JSONArray().put(JSONObject().put("id", files.id)
      .put("attributes", JSONObject().put("name", "Files")
        .put("platformAttributes", JSONObject().put("ios", platform)))))
      .toString().toByteArray()
  }

  @Test fun extendedArtworkWinsOverTheFixedRaster() {
    val result = AppleIconClient.parseArtwork(catalog(true), files, options, 42L)

    assertEquals(extended, result.template)
    assertEquals(42L, result.checkedAt)
    assertFalse(result.fallback)
    assertFalse(result.limited)
    val dark = AppleIconPolicy.rendition(result.template, options, true)
    assertTrue(dark.endsWith("/512x512Icon.ApSiOS01.png?appearance=dark"))
    assertTrue(dark.contains("/Placeholder.mill/"))
  }

  @Test fun catalogRasterFallbackIsLimitedLikeTheLookupFallback() {
    val result = AppleIconClient.parseArtwork(catalog(false), files, options, 42L)

    assertEquals(raster, result.template)
    assertTrue(result.fallback)
    assertTrue(result.limited)
  }

  @Test fun lookupRasterFallbackIsLimited() {
    val body = JSONObject().put("results", JSONArray().put(JSONObject()
      .put("trackId", files.id).put("trackName", "Files").put("artistName", "Apple")
      .put("artworkUrl512", raster.replace("/{w}x{h}{c}.{f}", "/512x512bb.jpg"))))
      .toString().toByteArray()
    val result = AppleIconClient.parseStandardArtwork(body, files, options, 42L)

    assertEquals(raster, result.template)
    assertTrue(result.fallback)
    assertTrue(result.limited)
  }

  @Test fun iosArtworkIsNotReusedForMac() {
    assertThrows(IOException::class.java) {
      AppleIconClient.parseArtwork(catalog(true), files, AppleIconOptions(platform = ApplePlatform.MACOS), 42L)
    }
  }

  @Test fun anotherProductCannotSupplyTheRequestedArtwork() {
    assertThrows(IOException::class.java) {
      AppleIconClient.parseArtwork(catalog(true), files.copy(id = "1584216193"), options, 42L)
    }
  }
}
