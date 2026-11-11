package os.kei.hyper.home.iconpack

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only captures through the framework path used by applications outside module scopes. */
@RunWith(AndroidJUnit4::class)
class NativeIconStyleProbeDeviceTest {
  @Test fun captureFrameworkLoadedIcons() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val phase = InstrumentationRegistry.getArguments().getString("phase") ?: "before"
    require(phase in setOf("before", "after"))
    val folder = File(context.cacheDir, "icon-style-probe-$phase").apply { mkdirs() }
    val pm = context.packageManager
    val targets = setOf("com.xiaomi.mirror", "com.xiaomi.gamecenter.sdk.service",
      "com.xiaomi.aiasst.service", "com.miui.cloudservice", "com.android.systemui", "os.kei.hyper.home")
    val report = JSONArray()
    @Suppress("DEPRECATION")
    val applications = pm.getInstalledApplications(0)
    for (app in applications) {
      val label = pm.getApplicationLabel(app).toString()
      if (app.packageName !in targets && !label.contains("Glasmio", ignoreCase = true)) continue
      val drawable = app.loadIcon(pm)
      val image = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
      try {
        drawable.setBounds(0, 0, 192, 192)
        drawable.draw(Canvas(image))
        File(folder, app.packageName + ".png").outputStream().use {
          check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        report.put(JSONObject().put("packageName", app.packageName).put("label", label)
          .put("drawable", drawable.javaClass.name).put("declaredIcon", app.icon)
          .put("topLeftArgb", image.getPixel(0, 0).toUInt().toString(16)))
      } finally { image.recycle() }
    }
    check(report.length() >= 4) { "Expected installed representative system apps" }
    File(folder, "report.json").writeText(report.toString(2))
  }
}
