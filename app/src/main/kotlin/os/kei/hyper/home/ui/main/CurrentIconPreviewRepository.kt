package os.kei.hyper.home.ui.main

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import os.kei.hyper.home.data.AppIconPreviews
import os.kei.hyper.home.data.IconPreviewRepository
import os.kei.hyper.home.data.InstalledIconCapability
import os.kei.hyper.home.data.PackageRefreshTarget
import os.kei.hyper.home.iconpack.IconPackDescriptor
import os.kei.hyper.home.iconpack.IconPackEntry
import os.kei.hyper.home.iconpack.IconPackPreviews
import os.kei.hyper.home.iconpack.IconPackSource
import os.kei.hyper.home.iconpack.IconMaskPolicy
import os.kei.hyper.home.iconpack.NativeAdaptiveIconMask
import os.kei.hyper.home.iconpack.NativeIconShapeConfig

/** Caches the complete preview, including native day/night overrides and Monet masks. */
internal class CurrentIconPreviewRepository(
  context: Context,
  private val loadArtwork: suspend (IconPackDescriptor, IconPackEntry) -> IconPackPreviews,
  dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
  private val application = context.applicationContext
  private val worker = dispatcher.limitedParallelism(2)
  private val lock = Any()
  private var generation = 0L
  private val requests = Array(32) { Mutex() }
  private val running = Semaphore(2)
  private val maskLock = Any()
  private val masks = LruCache<MaskKey, PublishedMask>(8)
  private val cache = object : LruCache<Key, AppIconPreviews>(4 * 1024 * 1024) {
    override fun sizeOf(key: Key, value: AppIconPreviews): Int = maxOf(1024,
      listOfNotNull(value.light, value.dark, value.lightMonochrome, value.darkMonochrome,
        value.backgroundMask).distinct().sumOf { it.allocationByteCount })
  }

  suspend fun loadOriginal(source: IconBrowserSource?, app: InstalledIconCapability,
    originals: IconPreviewRepository): AppIconPreviews {
    val epoch = synchronized(lock) { generation }
    return withContext(worker) {
      if (source == null || app.packageName in source.generation?.preservedIconPackages.orEmpty()) return@withContext originals.load(app)
      val file = (source.pack.source as? IconPackSource.Imported)?.record?.originalFile ?: return@withContext originals.load(app)
      if (!file.isFile) return@withContext originals.load(app)
      val maskKey = MaskKey(source.revision, file.absolutePath, file.length(), file.lastModified(), epoch)
      val cached = synchronized(maskLock) { masks.get(maskKey) }
      val mask = if (cached != null) cached.mask else ZipFile(file).use {
        publishedMask(it, file, source.revision, epoch)
      }
      val original = originals.load(app, colorMask = mask)
      return@withContext if (mask == null) original else original.copy(maskPathData = mask.pathData)
    }
  }

  suspend fun load(source: IconBrowserSource, entry: IconPackEntry): AppIconPreviews {
    val epoch = synchronized(lock) { generation }
    return withContext(worker) {
      val caller = currentCoroutineContext()
      caller.ensureActive()
      val file = (source.pack.source as IconPackSource.Imported).record.originalFile
      val key = Key(source.revision, source.pack.id, entry.key, file.absolutePath,
        file.length(), file.lastModified(), Configuration(application.resources.configuration))
      requests[(key.hashCode() and Int.MAX_VALUE) % requests.size].withLock {
        synchronized(lock) { cache.get(key)?.let { return@withContext it } }
        running.withPermit {
          var day: Bitmap? = null
          var night: Bitmap? = null
          var mono: Bitmap? = null
          var background: Bitmap? = null
          val name = entry.nativeName
          val mask = ZipFile(file).use { zip ->
            val applied = publishedMask(zip, file, source.revision, epoch)
            fun png(path: String, colored: Boolean = false): Bitmap? {
              caller.ensureActive()
              val item = zip.getEntry(path) ?: return null
              if (item.size !in 1..MAX_BYTES) return null
              val bytes = zip.getInputStream(item).use { it.readNBytes(MAX_BYTES + 1) }
              if (bytes.size > MAX_BYTES) return null
              val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
              BitmapFactory.decodeByteArray(bytes, 0, bytes.size, dimensions)
              if (dimensions.outWidth !in 1..512 || dimensions.outHeight !in 1..512) return null
              var sample = 1
              while (maxOf(dimensions.outWidth, dimensions.outHeight) / sample > 256) sample *= 2
              caller.ensureActive()
              val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample; inScaled = false }) ?: return null
              return if (!colored) bitmap
              else IconMaskPolicy.clipIfExceeds(bitmap, applied, check = { caller.ensureActive() })
            }
            if (name in source.paletteNames) {
              mono = png("fancy_icons/$name/mask.png")
              background = png("fancy_icons/$name/bg.png")
            }
            if (name !in source.dynamicNames) {
              day = png("fancy_icons/$name/day.png", colored = true) ?: day
              night = png("fancy_icons/$name/night.png", colored = true) ?: night
            }
            applied
          }
          if (day == null || night == null) {
            val pair = loadArtwork(source.pack, entry)
            day = day ?: pair.light?.let {
              IconMaskPolicy.clipIfExceeds(it, mask, check = { caller.ensureActive() }, recycleSource = false)
            }
            night = night ?: pair.dark?.let {
              IconMaskPolicy.clipIfExceeds(it, mask, check = { caller.ensureActive() }, recycleSource = false)
            }
          }
          caller.ensureActive()
          val result = AppIconPreviews(day, night, mono, mono, backgroundMask = background,
            maskPathData = mask?.pathData)
          synchronized(lock) { if (epoch == generation) cache.put(key, result) }
          result
        }
      }
    }
  }

  private fun publishedMask(zip: ZipFile, file: File, revision: String, epoch: Long): NativeAdaptiveIconMask? {
    val key = MaskKey(revision, file.absolutePath, file.length(), file.lastModified(), epoch)
    return synchronized(maskLock) {
      masks.get(key)?.let { return@synchronized it.mask }
      val mask = try {
        zip.getEntry(NativeIconShapeConfig.PATH)?.let { item ->
          require(item.size in 1..NativeIconShapeConfig.MAX_BYTES.toLong())
          val bytes = zip.getInputStream(item).use { it.readNBytes(NativeIconShapeConfig.MAX_BYTES + 1) }
          IconMaskPolicy.fromConfig(bytes)
        }
      } catch (cancelled: CancellationException) { throw cancelled
      } catch (_: Exception) { null }
      mask.also { masks.put(key, PublishedMask(it)) }
    }
  }

  fun sizeBytes(): Int = synchronized(lock) { cache.size() }
  fun clear() = synchronized(lock) { generation++; cache.evictAll() }

  internal fun invalidatePackages(targets: Set<PackageRefreshTarget>) = synchronized(lock) {
    generation++
    cache.snapshot().keys.filter { key ->
      key.packageName != null && targets.any { it.matches(key.packageName, key.userSerial) }
    }.forEach(cache::remove)
  }

  private data class Key(val revision: String, val pack: String, val entry: String,
    val path: String, val bytes: Long, val modified: Long, val configuration: Configuration,
    val packageName: String? = null, val userSerial: Long = -1L)
  private data class MaskKey(val revision: String, val path: String, val bytes: Long, val modified: Long, val epoch: Long)
  private data class PublishedMask(val mask: NativeAdaptiveIconMask?)

  companion object { private const val MAX_BYTES = 4 * 1024 * 1024 }
}
