package os.kei.hyper.home.xposed

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class LocalLottieIconValidatorTest {
  @Test fun admitsPinnedOriginalBrowserVectorWithoutChangingBytes() {
    val bytes = requireNotNull(javaClass.getResourceAsStream(
      "/os/kei/hyper/home/xposed/lottie/HyperOSClassic-com.android.browser.json",
    )).use { it.readBytes() }
    assertEquals(143961, bytes.size)
    val before = bytes.copyOf()
    val sha = MessageDigest.getInstance("SHA-1").apply {
      update("blob ${bytes.size}\u0000".toByteArray(Charsets.US_ASCII))
    }.digest(bytes).joinToString("") { "%02x".format(it) }
    // Perfect-Icons-Completion-Project @ fb8b5c9224510fedb653a7dceafdcb364b4e2263
    // themes/HyperOSClassic/layer_animating_icons/com.android.browser/1/fancy/com.android.browser.json
    assertEquals("930fd16d12ee4fa193930f1fbe55b64c495bf7fa", sha)
    LocalLottieIconValidator.validate(bytes)
    org.junit.Assert.assertArrayEquals(before, bytes)
  }

  @Test fun admitsMinimalVectorAndNumericEasingX() {
    val document = vector()
    val easing = JSONObject().put("x", JSONArray(listOf(0.5))).put("y", JSONArray(listOf(1)))
    val keyframe = JSONObject().put("t", 0).put("s", JSONArray(listOf(0, 0)))
      .put("e", JSONArray(listOf(1, 1))).put("i", easing)
    layer(document).getJSONObject("ks").put("p", JSONObject().put("a", 1).put("k", JSONArray().put(keyframe)))
    accept(document)
    val noAssets = vector().apply { remove("assets"); remove("markers") }
    accept(noAssets)
  }

  @Test fun rejectsExpressionsIncludingEscapedNestedFieldNames() {
    val document = vector()
    layer(document).getJSONObject("ks").getJSONObject("p").put("x", "fetch('https://example.invalid')")
    reject(document)
    reject(document.toString().replace("\"x\":", "\"\\u0078\":"))
    for (key in listOf("expression", "script", "scripts", "expr", "code", "js", "Expression")) {
      val bad = vector()
      layer(bad).getJSONObject("ks").put(key, "while(true){}")
      reject(bad)
    }
  }

  @Test fun rejectsImageAssetsAndMalformedPrecompositions() {
    for (asset in listOf(
      JSONObject().put("id", "image").put("u", "https://example.invalid/").put("p", "icon.png"),
      JSONObject().put("id", "image").put("u", "").put("p", "../icon.png"),
      JSONObject().put("id", "image").put("e", 1).put("p", "data:image/png;base64,AAAA"),
      JSONObject().put("id", "precomp").put("layers", JSONArray()),
    )) reject(vector().put("assets", JSONArray().put(asset)))
    reject(vector().put("assets", JSONObject()))
  }

  @Test fun admitsInlineVectorCompositionsAndLocalTrackMattes() {
    val source = layer(vector()).put("td", 1)
    val destination = layer(vector()).put("ind", 2).put("tt", 2).put("tp", 1).put("ct", 1)
    val document = vector().put("props", JSONObject()).put("assets", JSONArray().put(
      asset("comp_0", source, destination),
    )).put("layers", JSONArray().put(composition("comp_0")))
    accept(document)
    destination.remove("tp")
    accept(document)
    destination.put("tp", 3)
    reject(document)
    destination.put("tp", 2)
    reject(document)
    destination.put("tp", 1)
    document.put("props", JSONObject().put("custom", "value"))
    reject(document)
  }

  @Test fun rejectsMissingDuplicateCyclicAndOverBudgetPrecompositions() {
    fun withAssets(vararg assets: JSONObject) =
      vector().put("layers", JSONArray().put(composition("entry"))).put("assets", JSONArray(assets.toList()))
    reject(withAssets())
    reject(withAssets(asset("entry", layer(vector())), asset("entry", layer(vector()))))
    reject(withAssets(asset("entry", composition("entry"))))
    reject(withAssets(asset("entry", composition("child")), asset("child", composition("entry"))))
    reject(withAssets(asset("entry", layer(vector()).put("ty", 2))))
    reject(withAssets(asset("entry", layer(vector())).put("p", "https://example.invalid/image.png")))
    val depth = (0..8).map { index ->
      val id = if (index == 0) "entry" else "comp_$index"
      asset(id, if (index == 8) layer(vector()) else composition("comp_${index + 1}"))
    }
    reject(withAssets(*depth.toTypedArray()))
    val large = composition("child").put("w", 4096).put("h", 4096)
    reject(withAssets(asset("entry", large), asset("child", layer(vector()))))
  }

  @Test fun rejectsReferencesPathsAndUrlsInResourceFieldsAtAnyDepth() {
    for ((key, value) in listOf(
      "p" to "../icon.png", "u" to "https://example.invalid/", "refId" to "asset",
      "src" to "file:///etc/passwd", "url" to "javascript:alert(1)", "href" to "//example.invalid",
      "path" to "/sdcard/image.png", "fileName" to "icon.png", "fPath" to "content://provider/font",
    )) {
      val document = vector()
      layer(document).getJSONObject("ks").put(key, value)
      reject(document)
    }
  }

  @Test fun rejectsRasterTextEffectsProceduralAndUnknownShapeLayers() {
    for (type in listOf(0, 1, 2, 5, 6, 13)) {
      val document = vector()
      layer(document).put("ty", type)
      reject(document)
    }
    for (type in listOf("rp", "sr", "mm", "text", "unknown")) {
      val document = vector()
      layer(document).getJSONArray("shapes").getJSONObject(0).put("ty", type)
      reject(document)
    }
    val effects = vector()
    layer(effects).put("ef", JSONArray())
    reject(effects)
  }

  @Test fun enforcesByteDimensionsFrameRateTimelineAndVersionLimits() {
    reject(ByteArray(1024 * 1024 + 1) { ' '.code.toByte() })
    val minimal = vector().toString().toByteArray()
    val exact = ByteArray(1024 * 1024) { ' '.code.toByte() }
    minimal.copyInto(exact)
    LocalLottieIconValidator.validate(exact)
    for (key in listOf("w", "h")) {
      for (value in listOf(0, -1, 4097, 0.5, "351")) reject(vector().put(key, value))
    }
    for (rate in listOf(0, -1, 120.1, "60")) reject(vector().put("fr", rate))
    for ((start, end) in listOf(1 to 1, 2 to 1, 0 to 36001, -36001 to 0, -36000 to 36000)) {
      reject(vector().put("ip", start).put("op", end))
    }
    for (version in listOf("", "javascript:1", "../5.8.1", "5", "5.x", 5)) reject(vector().put("v", version))
    reject(vector().toString().replace("\"fr\":60", "\"fr\":1e309"))
    reject(vector().toString().replace("\"fr\":60", "\"fr\":NaN"))
    val invalidLayer = vector()
    layer(invalidLayer).put("op", 36001)
    reject(invalidLayer)
  }

  @Test fun rejectsExcessiveDepthBeforeUnboundedJsonConstruction() {
    val nested = "[".repeat(1000) + "0" + "]".repeat(1000)
    reject(vector().toString().replace("\"assets\":[]", "\"assets\":$nested"))
  }

  @Test fun boundsArrayObjectValueStringAndShapeCounts() {
    val manyItems = JSONArray()
    repeat(4097) { manyItems.put(0) }
    val tooManyItems = vector()
    layer(tooManyItems).getJSONObject("ks").put("k", manyItems)
    reject(tooManyItems)
    val manyObjects = JSONArray()
    repeat(4096) { manyObjects.put(JSONObject().put("k", 0)) }
    val tooManyObjects = vector()
    layer(tooManyObjects).getJSONObject("ks").put("k", manyObjects)
    reject(tooManyObjects)
    val manyValues = JSONArray()
    repeat(128) { manyValues.put(JSONArray(List(512) { 0 })) }
    val tooManyValues = vector()
    layer(tooManyValues).getJSONObject("ks").put("k", manyValues)
    reject(tooManyValues)
    reject(vector().put("nm", "a".repeat(4097)))
    val tooManyShapes = vector()
    layer(tooManyShapes).put("shapes", JSONArray(List(2049) { JSONObject().put("ty", "tr") }))
    reject(tooManyShapes)
    val tooManyLayers = vector()
    tooManyLayers.put("layers", JSONArray(List(129) { layer(vector()).put("ind", it) }))
    reject(tooManyLayers)
  }

  @Test fun rejectsParentCyclesMissingParentsAndUnboundedTimeStretch() {
    val self = vector()
    layer(self).put("parent", 1)
    reject(self)
    val missing = vector()
    layer(missing).put("parent", 42)
    reject(missing)
    val cycle = vector()
    layer(cycle).put("parent", 2)
    cycle.getJSONArray("layers").put(layer(vector()).put("ind", 2).put("parent", 1))
    reject(cycle)
    for (stretch in listOf(0, -1, 1001)) {
      val document = vector()
      layer(document).put("sr", stretch)
      reject(document)
    }
  }

  @Test fun requiresStrictTopLevelObjectUtf8AndJsonGrammar() {
    reject(JSONArray().put(vector()).toString())
    reject(vector().toString() + "{}")
    reject(vector().toString().replace("\"w\":351", "\"w\":351,\"w\":352"))
    reject(vector().toString().replace("\"w\":351", "\"w\":0351"))
    reject(vector().toString().replace("\"w\":351", "\"w\":+351"))
    reject(vector().toString().replace("\"w\":351", "\"w\":null"))
    reject(vector().toString().replace("\"w\":351", "\"w\":351,/*comment*/\"x\":1"))
    reject(vector().toString().replace("\"nm\":\"vector\"", "\"nm\":\"\\uD800\""))
    reject(byteArrayOf(0xc3.toByte(), 0x28))
    reject("{'v':'5.8.1'}")
    reject(vector().toString().dropLast(1) + ",}")
  }

  private fun vector(): JSONObject = JSONObject("""{
    "v":"5.8.1","fr":60,"ip":0,"op":60,"w":351,"h":351,"nm":"vector",
    "assets":[],"markers":[],
    "layers":[{"ind":1,"ty":4,"ip":0,"op":60,"ks":{"p":{"a":0,"k":[0,0]}},
      "shapes":[{"ty":"el","p":{"a":0,"k":[0,0]},"s":{"a":0,"k":[10,10]}}]}]
  }""")

  private fun layer(document: JSONObject): JSONObject = document.getJSONArray("layers").getJSONObject(0)
  private fun asset(id: String, vararg layers: JSONObject): JSONObject =
    JSONObject().put("id", id).put("fr", 60).put("layers", JSONArray(layers.toList()))

  private fun composition(id: String): JSONObject =
    layer(vector()).apply { remove("shapes") }.put("ty", 0).put("refId", id).put("w", 351).put("h", 351)

  private fun accept(document: JSONObject) = LocalLottieIconValidator.validate(document.toString().toByteArray())
  private fun reject(document: JSONObject) = reject(document.toString())
  private fun reject(text: String) = reject(text.toByteArray(Charsets.UTF_8))
  private fun reject(bytes: ByteArray) {
    try {
      LocalLottieIconValidator.validate(bytes)
      fail("Expected Lottie admission rejection")
    } catch (_: Exception) { }
  }
}
