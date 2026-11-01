package os.kei.hyper.home.xposed

import java.io.File
import java.util.UUID

/** The active provider can report the Compound parent, while import returns its Icon child. */
internal object NativeIconComponentIdentity {
  fun fromProvider(resourcePath: String?, observedHash: String?): String? {
    if (resourcePath == null || observedHash == null ||
      !observedHash.matches(Regex("[a-fA-F0-9]{40}"))) return null
    val metadata = File(resourcePath)
    if (!metadata.isAbsolute || metadata.extension != "mrm" ||
      metadata.parentFile?.name != "icons" || metadata.parentFile?.parentFile?.name != "meta"
    ) return null
    // Native Icon metadata is keyed by its component ID. Read the provider's path,
    // not the private file: system_server has no ext_data_rw group for that file.
    val componentId = metadata.nameWithoutExtension
    val canonical = try { UUID.fromString(componentId).toString() }
      catch (_: IllegalArgumentException) { return null }
    if (canonical != componentId) return null
    return componentId
  }
}
