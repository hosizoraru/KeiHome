package os.kei.hyper.home.iconpack

import android.content.Context
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException

/** Refreshes a proven generated mask without changing saved artwork or the user's mapping. */
internal object CustomIconGeometry {
  suspend fun refresh(context: Context, repository: IconPackRepository, check: () -> Unit = {}) {
    val pending = CustomIconStore.selections(context)
      .filter { it.captureRevision < CustomIconStore.CAPTURE_REVISION }
    if (pending.isEmpty()) return
    val sources = repository.listBuiltins().packs.filter { it.colorOsProfile != null }.associateBy { it.id }
    for (selection in pending) {
      check()
      // Only the exact immutable builtin source establishes a safe migration.
      val pack = sources[selection.sourceId] ?: continue
      val entry = pack.entries.singleOrNull { it.key == selection.sourceEntry } ?: continue
      val target = entry.component ?: continue
      val resources = CustomIconStore.readSelection(context, selection, check) ?: continue
      val replacement = try {
        regenerate(context, pack, target, resources, check) ?: continue
      } catch (cancelled: CancellationException) { throw cancelled
      } catch (interrupted: InterruptedException) { throw interrupted
      } catch (failure: Exception) {
        android.util.Log.w("KeiHomeCustomIcons", "Cannot refresh saved mask for ${selection.packageName}", failure)
        continue
      }
      CustomIconStore.refresh(context, selection, replacement, check)
    }
  }

  internal fun regenerate(
    context: Context, pack: IconPackDescriptor, target: IconPackComponent,
    resources: Map<String, ByteArray>, check: () -> Unit,
  ): Map<String, ByteArray>? {
    val root = "fancy_icons/custom.icon/"
    val day = resources[root + "day.png"] ?: return null
    val night = resources[root + "night.png"] ?: day
    val oldMask = resources[root + "mask.png"] ?: return null
    val background = resources[root + "bg.png"] ?: return null
    val manifest = resources[root + "manifest.xml"] ?: return null
    if (!NativePaletteIcon.recognizes("custom.icon", manifest, day, night, oldMask, background)) return null
    val size = GeneratedIconPng.squareSize(day)
    check()
    val png = IconPackRenderer(context).open(pack, false).use { session ->
      val mask = session.colorOsMonochrome(target, size, check) ?: return null
      try {
        ByteArrayOutputStream().also {
          check()
          require(mask.compress(Bitmap.CompressFormat.PNG, 100, it))
        }.toByteArray()
      } finally { mask.recycle() }
    }
    GeneratedIconPng.requireAlphaMask(png, size)
    check()
    return resources + (root + "mask.png" to png)
  }
}
