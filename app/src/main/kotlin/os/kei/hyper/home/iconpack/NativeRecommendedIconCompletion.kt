package os.kei.hyper.home.iconpack

import android.content.Context
import os.kei.hyper.home.data.RecommendedIconMappings
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.File

/** Keep native channel closures intact; render closed pairs when no native donor is available. */
internal object NativeRecommendedIconCompletion {
  fun additions(context: Context, pack: IconPackDescriptor, targets: List<IconPackComponent>, size: Int,
    maskPolicy: IconMaskPolicy?, preserved: Set<String>, recommendedMappings: RecommendedIconMappings,
    packageMappingExclusions: Set<String>, nativeArchive: File, limits: IconPackLimits,
    check: () -> Unit): Map<String, ByteArray> {
    if (!recommendedMappings.telegramChannels && !recommendedMappings.phoneCompanions &&
      !recommendedMappings.weiboLite) return emptyMap()
    val family = TelegramChannelIcons(pack.entries, recommendedMappings.telegramChannels)
    val packageMappings = RecommendedPackageIcons(pack.entries, recommendedMappings)
    val sourceEntries = IconPackEntryIndex(pack.entries)
    fun existing(target: IconPackComponent) = sourceEntries.find(target)
    val wanted = targets.filter { target ->
      target.packageName !in preserved && ((target.packageName !in packageMappingExclusions &&
        packageMappings.donor(target, existing(target)) != null) ||
        (TelegramChannelIcons.isDefaultTarget(target) &&
        existing(target) == null && family.donor(target, true, null) != null &&
        !(target.className == null && targets.any {
          it.packageName == target.packageName && it.className != null && existing(it) != null
        })))
    }
    if (wanted.isEmpty()) return emptyMap()
    val native = family.nativeAdditions(nativeArchive, wanted, ::existing, limits, check)
    val output = LinkedHashMap(native.resources)
    val written = native.names.toMutableSet()
    val remaining = wanted.filter { (nativeLauncherActivityName(it) ?: it.packageName) !in written }
    if (remaining.isEmpty()) return output
    val renderer = IconPackRenderer(context)
    renderer.open(pack, false, maskPolicy = maskPolicy, clipOutput = false,
      preservedPackages = preserved, recommendedMappings = recommendedMappings).use { day ->
      renderer.open(pack, true, maskPolicy = maskPolicy, clipOutput = false,
        preservedPackages = preserved, recommendedMappings = recommendedMappings).use { night ->
        fun png(session: IconPackRenderer.Session, target: IconPackComponent): ByteArray? {
          val raw = session.renderTarget(null, target, size, check) ?: return null
          val bitmap = session.maskOutput(raw)
          return try { ByteArrayOutputStream().use { bytes ->
            check()
            require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes))
            bytes.toByteArray()
          } } finally { bitmap.recycle() }
        }
        for (target in remaining) {
          check()
          val light = png(day, target) ?: continue
          val dark = png(night, target) ?: continue
          for (name in listOfNotNull(nativeLauncherActivityName(target), target.packageName).distinct()) {
            if (!written.add(name)) continue
            output.putAll(NativeDayNightIcon.artworkEntries(name, light, dark,
              requiresNativeDescriptor = target.packageName in RecommendedPackageIcons.PHONE_TARGETS))
          }
        }
      }
    }
    return output
  }
}
