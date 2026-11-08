package os.kei.hyper.home.xposed

import java.lang.reflect.Field
import java.lang.reflect.Modifier

/** Best-effort, bounded cache hygiene. It never unregisters host callbacks or closes DEX. */
internal object ModulePlatformRoots {
  fun release(loader: ClassLoader): String {
    var parcel = 0
    var traces = 0
    var retained = 0
    var unavailable = 0
    try {
      val type = Class.forName("android.os.Parcel")
      val paired = type.field("sPairedCreators")
      val creators = type.field("mCreators")
      check(Modifier.isStatic(paired.modifiers) && Modifier.isStatic(creators.modifiers))
      val lock = paired.get(null) as? Map<*, *> ?: error("Parcel lock unavailable")
      val legacy = creators.get(null) as? Map<*, *> ?: error("Parcel cache unavailable")
      parcel = countOwnedParcelBuckets(lock, legacy, loader)
    } catch (_: Exception) {
      unavailable++
    }
    try {
      val threadType = Class.forName("android.app.ActivityThread")
      val thread = threadType.getDeclaredMethod("currentActivityThread").invoke(null)
      val apks = ArrayList<Any>(2)
      if (thread != null) {
        val bound = threadType.field("mBoundApplication").get(thread)
        bound?.javaClass?.field("info")?.get(bound)?.let(apks::add)
        val system = threadType.field("mSystemContext").get(thread)
        system?.javaClass?.field("mPackageInfo")?.get(system)?.let {
          if (apks.none { previous -> previous === it }) apks.add(it)
        }
      }
      val apkType = Class.forName("android.app.LoadedApk")
      val backtrace = Throwable::class.java.field("backtrace")
      val locations = ArrayList<Throwable>()
      for (apk in apks) {
        for ((registry, dispatcher) in listOf(
          "mReceivers" to "ReceiverDispatcher", "mServices" to "ServiceDispatcher",
        )) {
          val map = apkType.field(registry).get(apk) as? Map<*, *> ?: continue
          val field = Class.forName("android.app.LoadedApk\$$dispatcher").field("mLocation")
          snapshotRegistrationTraces(map, field, locations)
        }
      }
      // Materialization can run managed code: do it outside LoadedApk's registry locks.
      for (trace in locations) {
        when (materializeOwnedTrace(trace, loader, backtrace)) {
          1 -> traces++
          -1 -> retained++
        }
      }
    } catch (_: Exception) {
      unavailable++
    }
    return "parcelBucketsDeferred=$parcel traceMaterialized=$traces traceRetained=$retained unavailable=$unavailable"
  }

  private fun snapshotRegistrationTraces(
    map: Map<*, *>, location: Field, output: MutableList<Throwable>,
  ) {
    synchronized(map) {
      require(map.size <= MAX_LOCATIONS) { "Registration registry exceeds bound" }
      for (value in map.values) {
        val registrations = value as? Map<*, *> ?: error("Unknown registration registry")
        require(registrations.size <= MAX_LOCATIONS - output.size) { "Registration bound exceeded" }
        for (dispatcher in registrations.values) {
          (location.get(dispatcher) as? Throwable)?.let(output::add)
        }
      }
    }
  }

  private fun Class<*>.field(name: String): Field =
    getDeclaredField(name).apply { isAccessible = true }

  private const val MAX_LOCATIONS = 1024
}

/**
 * Parcel unlocks while resolving the creator, then writes into the bucket it previously created.
 * The cache lock alone cannot prove there is no in-flight reader; observe rather than delete it.
 */
internal fun countOwnedParcelBuckets(
  paired: Map<*, *>, legacy: Map<*, *>, loader: ClassLoader,
): Int = synchronized(paired) {
  (if (paired.containsKey(loader)) 1 else 0) + (if (legacy.containsKey(loader)) 1 else 0)
}

internal fun ownsBacktrace(value: Any?, loader: ClassLoader): Boolean =
  (value as? Array<*>)?.any { it is Class<*> && it.classLoader === loader } == true

/** 0: unrelated; 1: owned backtrace released; -1: owned root remains. */
internal fun materializeOwnedTrace(trace: Throwable, loader: ClassLoader, backtrace: Field): Int {
  if (!ownsBacktrace(backtrace.get(trace), loader)) return 0
  if (trace.javaClass.classLoader === loader ||
    trace.javaClass.getMethod("getStackTrace").declaringClass !== Throwable::class.java) return -1
  trace.stackTrace
  return if (ownsBacktrace(backtrace.get(trace), loader)) -1 else 1
}
