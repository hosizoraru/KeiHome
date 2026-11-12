package os.kei.hyper.home.ui.main

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import os.kei.hyper.home.data.AppDisplayStorage
import os.kei.hyper.home.data.PreservedIcons
import os.kei.hyper.home.iconpack.IconArchiveVariant
import os.kei.hyper.home.iconpack.IconPackArtwork
import os.kei.hyper.home.iconpack.IconPackComponent
import os.kei.hyper.home.iconpack.IconPackDescriptor
import os.kei.hyper.home.iconpack.IconPackEntry
import os.kei.hyper.home.iconpack.IconPackFormat
import os.kei.hyper.home.iconpack.IconPackSource
import os.kei.hyper.home.iconpack.IconResourceQualifiers
import os.kei.hyper.home.iconpack.NativeIconShapeConfig
import os.kei.hyper.home.iconpack.NativeIconMaskImport
import os.kei.hyper.home.iconpack.StoredIconPack
import os.kei.hyper.home.status.SystemIconThemeReport

/** Temporary artwork and mask files; only the two exercised preference keys are restored. */
internal class MaskPreviewPolicyFixture : AutoCloseable {
  private val base = InstrumentationRegistry.getInstrumentation().targetContext
  private val root = File(base.cacheDir, "mask-preview-policy-${UUID.randomUUID()}").apply { mkdirs() }
  val context = object : ContextWrapper(base) {
    override fun getApplicationContext(): Context = this
    override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
    override fun getCacheDir(): File = root
  }
  private val preferences = AppDisplayStorage.open(base)
  private val previousPreserved = preferences.getStringSet(PreservedIcons.KEY, null)?.toSet()
  private val hadPreserved = preferences.contains(PreservedIcons.KEY)
  private val previousCompletion = preferences.getBoolean("complete_system_icon_config", true)
  private val hadCompletion = preferences.contains("complete_system_icon_config")
  private val file = File(root, "published.zip")
  private val artwork = IconPackArtwork.Archive(
    listOf(IconArchiveVariant("probe.png", IconResourceQualifiers("drawable", densityDpi = 0))))
  val entry = IconPackEntry("probe", component = IconPackComponent(base.packageName),
    nativeName = "probe", light = artwork, dark = artwork)
  val pack: IconPackDescriptor

  init {
    writePublished(CIRCLE, Color.BLUE)
    val record = StoredIconPack("policy-probe", "Probe", 0, IconPackFormat.MIUI_ICONS,
      file, file, file.length(), null)
    pack = IconPackDescriptor("policy-probe", "Probe", IconPackFormat.MIUI_ICONS,
      IconPackSource.Imported(record), listOf(entry))
    preferences.edit().putStringSet(PreservedIcons.KEY, setOf(base.packageName))
      .putBoolean("complete_system_icon_config", true).commit()
    writePending(SQUARE)
  }

  fun source(revision: String = "applied") = IconBrowserSource(revision, pack,
    mapOf("probe" to entry), emptySet(), emptySet())

  fun theme(revision: String = "applied") = SystemIconThemeReport(
    SystemIconThemeReport.Source.THEME, "Applied", null, 1, 1, true, revision,
    previewKey = revision)

  fun writePending(path: String) {
    val canonical = NativeIconMaskImport.canonicalPath(path)
    val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
      .joinToString("") { "%02x".format(it) }
    File(context.filesDir, "icon-shape/custom-mask.json").apply {
      parentFile!!.mkdirs()
      writeText(JSONObject().put("version", 1).put("pathData", canonical)
        .put("name", "Pending").put("digest", digest).toString())
    }
  }

  fun writePublished(path: String, color: Int) {
    ZipOutputStream(file.outputStream()).use { zip ->
      fun write(name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
      }
      write(NativeIconShapeConfig.PATH, NativeIconShapeConfig.generated(path))
      write("probe.png", png(Color.RED))
      write("fancy_icons/probe/day.png", png(color))
      write("fancy_icons/probe/night.png", png(color))
    }
  }

  override fun close() {
    preferences.edit().apply {
      if (hadPreserved) putStringSet(PreservedIcons.KEY, previousPreserved) else remove(PreservedIcons.KEY)
      if (hadCompletion) putBoolean("complete_system_icon_config", previousCompletion)
      else remove("complete_system_icon_config")
    }.commit()
    root.deleteRecursively()
  }

  private fun png(color: Int): ByteArray {
    val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
    return try {
      ByteArrayOutputStream().use {
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        it.toByteArray()
      }
    } finally { bitmap.recycle() }
  }

  companion object {
    const val CIRCLE = "M50 0 A50 50 0 1 1 50 100 A50 50 0 1 1 50 0 Z"
    const val SQUARE = "M0 0 H100 V100 H0 Z"
  }
}
