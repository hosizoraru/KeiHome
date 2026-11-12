package os.kei.hyper.home.iconpack

import android.content.Context
import os.kei.hyper.home.data.RecommendedIconMappings
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/** Add closed image-only pairs; never copy or rewrite an authored third-party program. */
internal object NativeTelegramChannelCompletion {
  fun additions(context: Context, pack: IconPackDescriptor, targets: List<IconPackComponent>, size: Int,
    maskPolicy: IconMaskPolicy?, preserved: Set<String>, recommendedMappings: RecommendedIconMappings,
    check: () -> Unit): Map<String, ByteArray> {
    if (!recommendedMappings.telegramChannels) return emptyMap()
    val family = TelegramChannelIcons(pack.entries)
    val byComponent = pack.entries.mapNotNull { entry -> entry.component?.let { it to entry } }.toMap()
    val byName = pack.entries.mapNotNull { entry -> entry.nativeName?.let { it to entry } }.toMap()
    fun existing(target: IconPackComponent) = byComponent[target]
      ?: byComponent[IconPackComponent(target.packageName)]
      ?: nativeLauncherIconNames(target).firstNotNullOfOrNull { byName[it] }
    val wanted = targets.filter { target ->
      target.packageName !in preserved && TelegramChannelIcons.isDefaultTarget(target) &&
        existing(target) == null && family.donor(target, true, null) != null &&
        !(target.className == null && targets.any {
          it.packageName == target.packageName && it.className != null && existing(it) != null
        })
    }
    if (wanted.isEmpty()) return emptyMap()
    val output = linkedMapOf<String, ByteArray>()
    val written = hashSetOf<String>()
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
        for (target in wanted) {
          check()
          val light = png(day, target) ?: continue
          val dark = png(night, target) ?: continue
          for (name in listOfNotNull(nativeLauncherActivityName(target), target.packageName).distinct()) {
            if (!written.add(name)) continue
            output["res/drawable-xxhdpi/$name.png"] = light
            output["nightmode/res/drawable-xxhdpi/$name.png"] = dark
            output.putAll(NativeDayNightIcon.entries(name, light, dark))
          }
        }
      }
    }
    return output
  }
}
