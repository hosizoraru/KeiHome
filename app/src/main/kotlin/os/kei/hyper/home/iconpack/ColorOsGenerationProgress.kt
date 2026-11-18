package os.kei.hyper.home.iconpack

internal enum class ColorOsGenerationStage {
  APPLICATIONS, LIGHT, DARK, PAIRING, ADAPTATIONS, STAMPING, MASKING, MONET, CUSTOM_ICONS, PACKAGING
}

internal data class ColorOsGenerationProgress(
  val stage: ColorOsGenerationStage,
  val done: Int = 0,
  val total: Int = 0,
  val packageName: String? = null,
  val inKiB: Boolean = false,
)

/** Report from the worker without queuing one Main-thread coroutine per icon. */
internal class ColorOsProgressReporter(
  private val report: (ColorOsGenerationProgress) -> Unit,
  private val now: () -> Long = System::nanoTime,
) {
  private var previous: ColorOsGenerationProgress? = null
  private var lastNanos = 0L

  fun update(value: ColorOsGenerationProgress) {
    val time = now()
    if (value == previous) return
    if (previous?.stage != value.stage || previous?.total != value.total ||
      value.done == value.total || time - lastNanos >= 100_000_000L) {
      report(value)
      previous = value
      lastNanos = time
    }
  }
}

/** One generation, sequential access, bounded metadata/resources; no Bitmap retention. */
internal class ColorOsGenerationCache<K, V>(private val capacity: Int) {
  init { require(capacity > 0) }
  private val entries = LinkedHashMap<K, V>(capacity, 0.75f, true)

  fun get(key: K, load: () -> V): V {
    if (entries.containsKey(key)) return entries.getValue(key)
    val value = load()
    entries[key] = value
    if (entries.size > capacity) entries.remove(entries.keys.first())
    return value
  }
}
