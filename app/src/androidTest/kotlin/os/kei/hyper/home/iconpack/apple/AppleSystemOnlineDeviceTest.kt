package os.kei.hyper.home.iconpack.apple

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit network smoke test: pass -e appleOnline true; normal test runs stay offline. */
@RunWith(AndroidJUnit4::class)
class AppleSystemOnlineDeviceTest {
  @Test fun messagesCatalogSupportsIndependentAppearancesFromTheAppUid() = runBlocking {
    assumeTrue(InstrumentationRegistry.getArguments().getString("appleOnline") == "true")
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val report = File(context.cacheDir, "apple-online-diagnostic.json")
    val requests = JSONArray()
    val http = AppleHttpTransport()
    val client = AppleIconClient(AppleIconTransport { url ->
      val record = JSONObject().put("url", url)
      requests.put(record)
      try {
        http.get(url).also { bytes -> record.put("response", String(bytes)) }
      } catch (failure: Exception) {
        record.put("failure", failure.toString())
        throw failure
      } finally {
        report.writeText(requests.toString(2))
      }
    })
    val artwork = client.artwork(AppleAppMatch("1146560473", "Messages", "Apple"),
      AppleIconOptions(country = "us"))
    assertFalse("Inspect ${report.path}", artwork.limited)
  }
}
