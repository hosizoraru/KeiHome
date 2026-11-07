package os.kei.hyper.home.iconpack.apple

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.graphics.BitmapFactory
import android.os.Process
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import os.kei.hyper.home.data.AppLocalStorage
import os.kei.hyper.home.iconpack.IconPackArchive
import os.kei.hyper.home.iconpack.IconPackDescriptor
import os.kei.hyper.home.iconpack.IconPackSource

internal data class AppleGenerationProgress(val done: Int, val total: Int, val label: String)
internal data class AppleGenerationResult(val pack: IconPackDescriptor, val mapped: Int,
  val dark: Int, val reused: Int, val failed: Int, val unchanged: Boolean, val stale: Int)
internal data class AppleArtworkPreview(val app: AppleAppMatch, val light: File, val dark: File?)
internal class ApplePackSizeException : IOException("Apple pack exceeds 300 MiB")

/** Download cache is evictable; the published immutable pack and user mappings are not. */
internal class AppleIconRepository(context: Context, private val client: AppleIconClient = AppleIconClient(),
  directory: File = File(context.noBackupFilesDir, "apple-icons"), namespace: String = "apple_icons") {
  private val context = context.applicationContext
  private val settings = AppLocalStorage.open(context, namespace)
  private val root = directory
  private val downloads = File(root, "downloads")
  private val metadata = File(downloads, "metadata")
  private val images = File(downloads, "images")
  private val generated = File(root, "generated")
  private val publication = File(root, "current.json")
  private val mutex = Mutex()
  private val publicationLock = Any()
  private val archives = IconPackArchive()
  private var descriptorCache: Pair<String, IconPackDescriptor>? = null
  private val bundledRevision by lazy {
    context.assets.open("apple-system-icons/provenance.json").use { AppleIconPolicy.digest(it.readBytes()) }
  }

  fun options(): AppleIconOptions = runCatching {
    val value = JSONObject(settings.getString("options", null).orEmpty())
    AppleIconOptions(ApplePlatform.valueOf(value.getString("platform")), value.getInt("resolution"),
      AppleCorners.valueOf(value.getString("corners")), value.getString("country"))
  }.getOrDefault(AppleIconOptions())

  fun setOptions(options: AppleIconOptions) {
    settings.putString("options", options.json().toString())
  }

  fun match(target: AppleAppTarget, options: AppleIconOptions): AppleAppMatch? {
    val saved = settings.getString(mappingKey(target.packageName, options), null)
    if (saved == "-") return null
    if (saved != null) return runCatching { JSONObject(saved).match() }.getOrNull()
    return AppleSystemMappings.defaultFor(target, options.platform)
  }

  fun custom(target: AppleAppTarget, options: AppleIconOptions): Boolean =
    settings.contains(mappingKey(target.packageName, options))

  fun setMatch(target: AppleAppTarget, options: AppleIconOptions, app: AppleAppMatch?) {
    settings.putString(mappingKey(target.packageName, options), app?.json()?.toString() ?: "-")
  }

  fun resetMatch(target: AppleAppTarget, options: AppleIconOptions) {
    settings.remove(mappingKey(target.packageName, options))
  }

  suspend fun targets(): List<AppleAppTarget> = withContext(Dispatchers.IO) {
    val pm = context.packageManager
    context.getSystemService(LauncherApps::class.java)
      ?.getActivityList(null, Process.myUserHandle()).orEmpty()
      .distinctBy { it.applicationInfo.packageName }.mapNotNull {
        currentCoroutineContext().ensureActive()
        val app = it.applicationInfo
        if (app.packageName == context.packageName) return@mapNotNull null
        AppleAppTarget(app.packageName, app.loadLabel(pm).toString(),
          app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)
      }.sortedBy { it.label.lowercase(java.util.Locale.ROOT) }
  }

  suspend fun search(term: String, options: AppleIconOptions): List<AppleAppMatch> = mutex.withLock {
    withContext(Dispatchers.IO) {
      AppleSystemMappings.localSearch(term, options.platform).takeIf { it.isNotEmpty() }
        ?.let { return@withContext it.map { app -> app.copy(country = options.country) } }
      val file = File(metadata, AppleIconPolicy.key("search:${options.platform}:${options.country}:" +
        term.trim().lowercase(java.util.Locale.ROOT)) + ".json")
      readJson(file)?.takeIf { System.currentTimeMillis() - it.optLong("time") in 0..AppleIconPolicy.SEARCH_TTL_MS }
        ?.let { cached -> return@withContext cached.getJSONArray("apps").matches()
          .map { it.copy(country = options.country) } }
      val result = client.search(term, options)
      writeJson(file, JSONObject().put("time", System.currentTimeMillis())
        .put("apps", JSONArray(result.map { it.json() })))
      result
    }
  }

  suspend fun cacheBytes(): Long = withContext(Dispatchers.IO) {
    downloads.walkTopDown().filter { it.isFile }.sumOf { it.length() }
  }

  suspend fun clearDownloads() = mutex.withLock {
    withContext(Dispatchers.IO) { downloads.deleteRecursively(); Unit }
  }

  suspend fun preview(app: AppleAppMatch, options: AppleIconOptions): AppleArtworkPreview = mutex.withLock {
    withContext(Dispatchers.IO) {
      val effective = options.forApp(app)
      val pair = (if (!app.id.startsWith("system_")) previousPair(app, effective, readPublication()) else null)
        ?: checkNotNull(pair(app, effective, refresh = false))
      pruneImages(listOfNotNull(pair.light, pair.dark).toSet())
      AppleArtworkPreview(app, pair.light, pair.dark)
    }
  }

  /** Reads local caches and the published archive only; a miss never starts a request. */
  suspend fun cachedPreview(app: AppleAppMatch, options: AppleIconOptions): AppleArtworkPreview? = mutex.withLock {
    withContext(Dispatchers.IO) {
      try {
        val effective = options.forApp(app)
        val pair = previousPair(app, effective, readPublication())
          ?: pair(app, effective, refresh = false, cacheOnly = true) ?: return@withContext null
        pruneImages(listOfNotNull(pair.light, pair.dark).toSet())
        AppleArtworkPreview(app, pair.light, pair.dark)
      } catch (cancelled: CancellationException) { throw cancelled
      } catch (_: Exception) { null }
    }
  }

  suspend fun needsGeneration(targets: List<AppleAppTarget>, options: AppleIconOptions): Boolean =
    withContext(Dispatchers.IO) {
      val record = readPublication() ?: return@withContext true
      val assignments = targets.mapNotNull { target ->
        match(target, options)?.let { target.packageName to it.artworkId(options) }
      }
        .toMap().toSortedMap()
      record.optInt("schema") != 2 ||
        record.optJSONObject("options")?.toString() != options.json().toString() ||
        record.optString("bundled") != bundledRevision ||
        record.optJSONObject("mappings")?.toString() != JSONObject(assignments).toString()
    }

  suspend fun publishedOptions(): AppleIconOptions? = withContext(Dispatchers.IO) {
    readPublication()?.optJSONObject("options")?.let {
      runCatching { AppleIconOptions(ApplePlatform.valueOf(it.getString("platform")), it.getInt("resolution"),
        AppleCorners.valueOf(it.getString("corners")), it.getString("country")) }.getOrNull()
    }
  }

  suspend fun descriptor(): IconPackDescriptor? = withContext(Dispatchers.IO) { try {
    val record = readPublication() ?: return@withContext null
    val digest = record.optString("digest")
    if (!digest.matches(Regex("[0-9a-f]{64}"))) return@withContext null
    val file = File(generated, "$digest.zip")
    if (!file.isFile) return@withContext null
    val fingerprint = "$digest:${file.length()}:${file.lastModified()}"
    synchronized(publicationLock) { descriptorCache?.takeIf { it.first == fingerprint }?.second }
      ?.let { return@withContext it }
    val parsed = archives.inspect(file, File(generated, "nested-unused"))
    IconPackDescriptor("apple", "Apple", parsed.format,
      IconPackSource.Generated(file, digest, record.optLong("createdAt")),
      parsed.entries, parsed.description, parsed.previews, parsed.notices, parsed.directives)
      .also { synchronized(publicationLock) { descriptorCache = fingerprint to it } }
  } catch (cancelled: CancellationException) { throw cancelled
  } catch (failure: Exception) {
    android.util.Log.w("KeiHomeAppleIcons", "Could not read the generated Apple pack", failure)
    null
  } }

  suspend fun generate(targets: List<AppleAppTarget>, options: AppleIconOptions, refresh: Boolean = false,
    progress: (AppleGenerationProgress) -> Unit): AppleGenerationResult = mutex.withLock {
    withContext(Dispatchers.IO) { try {
      val selected = targets.mapNotNull { target -> match(target, options)?.let { target to it } }
      require(selected.isNotEmpty()) { "No Apple mappings" }
      generated.mkdirs()
      val resolved = linkedMapOf<String, PairFiles>()
      val assignments = linkedMapOf<String, String>()
      val previous = readPublication()
      var failed = 0
      var reused = 0
      var firstError: Exception? = null
      var sourceBytes = 0L
      for ((index, item) in selected.withIndex()) {
        currentCoroutineContext().ensureActive()
        val (target, app) = item
        val effective = options.forApp(app)
        val artworkId = app.artworkId(effective)
        progress(AppleGenerationProgress(index, selected.size, target.label))
        try {
          val pair = resolved[artworkId] ?: (
            if (!refresh && !app.id.startsWith("system_")) previousPair(app, effective, previous) else null
          ).let { cached -> cached ?: checkNotNull(pair(app, effective, refresh)) }.also {
            sourceBytes += it.light.length() + (it.dark?.length() ?: 0L)
            if (sourceBytes > 300L * 1024 * 1024) throw ApplePackSizeException()
            resolved[artworkId] = it
            if (it.reused) reused++
          }
          assignments[target.packageName] = pair.id
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) {
          if (failure is ApplePackSizeException) throw failure
          failed++
          if (firstError == null) firstError = failure
          val oldApp = previous?.optJSONObject("mappings")?.optString(target.packageName)
            ?.let { publishedApp(it, previous) }?.takeIf { it.country == effective.country }
          val retained = oldApp?.let {
            resolved[it.artworkId(effective)] ?: previousPair(it, effective, previous)
          }
          if (retained != null) {
            if (retained.id !in resolved) {
              sourceBytes += retained.light.length() + (retained.dark?.length() ?: 0L)
              if (sourceBytes > 300L * 1024 * 1024) throw ApplePackSizeException()
              reused++
            }
            resolved[retained.id] = retained
            assignments[target.packageName] = retained.id
          }
        }
      }
      if (assignments.isEmpty()) throw firstError ?: IOException("No Apple artwork")
      currentCoroutineContext().ensureActive()
      val identity = JSONObject().put("schema", 2).put("options", options.json()).put("bundled", bundledRevision)
        .put("mappings", JSONObject(assignments.toSortedMap()))
        .put("artwork", JSONObject(resolved.toSortedMap().mapValues { (_, pair) ->
          "${pair.light.name}:${pair.dark?.name.orEmpty()}"
        }))
      val digest = AppleIconPolicy.key(identity.toString())
      val archive = File(generated, "$digest.zip")
      val reusable = archive.isFile && runCatching { archives.inspect(archive, File(generated, "nested-unused")) }.isSuccess
      if (!reusable) {
        archive.delete()
        val temp = File.createTempFile("apple-", ".tmp", generated)
        try {
          val maxBytes = resolved.values.sumOf { it.light.length() + (it.dark?.length() ?: 0L) }
          require(maxBytes <= 300L * 1024 * 1024) { "Apple pack too large" }
          ZipOutputStream(temp.outputStream().buffered()).use { zip ->
            val appfilter = buildString {
              append("<?xml version=\"1.0\" encoding=\"utf-8\"?><resources>")
              assignments.toSortedMap().forEach { (pkg, id) ->
                require(pkg.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")))
                append("<item component=\"ComponentInfo{").append(pkg)
                  .append("}\" drawable=\"apple_").append(id).append("\"/>")
              }
              append("</resources>")
            }
            zip.text("appfilter.xml", appfilter)
            zip.text("description.xml", "<?xml version=\"1.0\" encoding=\"utf-8\"?><MIUI-Theme>" +
              "<title>Apple</title><author>Apple / App publishers</author><version>1</version></MIUI-Theme>")
            zip.text("apple-source-manifest.json", identity.toString())
            for (pair in resolved.values) {
              currentCoroutineContext().ensureActive()
              zip.png("res/drawable-nodpi/apple_${pair.id}.png", pair.light)
              pair.dark?.let { zip.png("res/drawable-night-nodpi/apple_${pair.id}.png", it) }
            }
          }
          archives.inspect(temp, File(generated, "nested-unused"))
          check(temp.renameTo(archive)) { "Cannot publish Apple archive" }
        } finally { temp.delete() }
      }
      // Replace only after every output is validated; interruption leaves the last pack usable.
      synchronized(publicationLock) {
        writeJson(publication, identity.put("digest", digest)
          .put("createdAt", if (previous?.optString("digest") == digest) previous.optLong("createdAt") else System.currentTimeMillis()))
      }
      pruneImages(resolved.values.flatMap { listOfNotNull(it.light, it.dark) }.toSet())
      // Keep the previous immutable archive for in-flight previews and conversion.
      generated.listFiles().orEmpty().filter { it.extension == "zip" && it != archive }
        .sortedByDescending { it.lastModified() }.drop(1).forEach { it.delete() }
      progress(AppleGenerationProgress(selected.size, selected.size, ""))
      AppleGenerationResult(checkNotNull(descriptor()), assignments.size,
        assignments.values.count { resolved[it]?.dark != null }, reused, failed,
        previous?.optString("digest") == digest, resolved.values.count { it.stale })
    } finally { pruneImages(emptySet()) } }
  }

  private data class PairFiles(val id: String, val light: File, val dark: File?, val reused: Boolean,
    val stale: Boolean = false)

  private suspend fun previousPair(app: AppleAppMatch, options: AppleIconOptions, record: JSONObject?): PairFiles? {
    val publishedOptions = record?.optJSONObject("options") ?: return null
    if (publishedOptions.optString("platform") != options.platform.name ||
      publishedOptions.optInt("resolution") != options.resolution ||
      publishedOptions.optString("corners") != options.corners.name) return null
    if ((app.id == "1108187390" || app.id.startsWith("system_")) &&
      record.optString("bundled") != bundledRevision) return null
    val resourceId = app.artworkId(options)
    // Legacy resources have no storefront suffix; only their recorded global country is proven.
    val id = when (record.optInt("schema", 1)) {
      1 -> app.id.takeIf { publishedOptions.optString("country") == options.country } ?: return null
      2 -> resourceId
      else -> return null
    }
    val oldDigest = record.optString("digest")
    if (!oldDigest.matches(Regex("[0-9a-f]{64}"))) return null
    val paths = record.optJSONObject("artwork")?.optString(id)?.split(':') ?: return null
    if (paths.size != 2) return null
    return try {
      suspend fun restore(name: String, night: Boolean): File {
        require(name.matches(Regex("[0-9a-f]{64}\\.png")))
        val file = File(images, name)
        if (file.isFile && validPng(file, options.resolution) &&
          AppleIconPolicy.digest(file.readBytes()) == name.removeSuffix(".png")) {
          file.setLastModified(System.currentTimeMillis())
          return file
        }
        val archive = File(generated, "$oldDigest.zip")
        val path = "res/drawable${if (night) "-night" else ""}-nodpi/apple_$id.png"
        currentCoroutineContext().ensureActive()
        val bytes = IconPackArchive.readEntry(archive, path, AppleIconPolicy.MAX_RESPONSE)
        require(AppleIconPolicy.digest(bytes) == name.removeSuffix(".png"))
        images.mkdirs()
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream)
        } catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
        require(validPng(file, options.resolution))
        return file
      }
      PairFiles(resourceId, restore(paths[0], false), paths[1].takeIf { it.isNotEmpty() }?.let { restore(it, true) }, true)
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (_: Exception) { null }
  }

  private suspend fun pair(app: AppleAppMatch, options: AppleIconOptions, refresh: Boolean,
    cacheOnly: Boolean = false): PairFiles? {
    if (app.id.startsWith("system_")) return systemIcon(app, options, cacheOnly)
    val key = AppleIconPolicy.key("art:${options.platform.attribute}:${options.country}:${app.id}")
    val metaFile = File(metadata, "$key.json")
    val cached = readJson(metaFile)?.let {
      runCatching { AppleArtwork(app, it.getString("template"), it.getLong("time"),
        it.optBoolean("fallback"), it.optBoolean("limited")) }.getOrNull()
    }
    val bundledMusic = app.id == "1108187390" && options.platform != ApplePlatform.MACOS
    if (bundledMusic && cached == null && !refresh) return systemIcon(app, options, cacheOnly)
    if (cacheOnly && cached == null) return null
    var stale = false
    val art = if (cached != null && !refresh) cached else {
      try {
        val fresh = client.artwork(app, options)
        if (fresh.limited && cached != null && !cached.limited) {
          stale = true
          cached
        } else fresh.also {
          writeJson(metaFile, JSONObject().put("template", it.template).put("time", it.checkedAt)
            .put("fallback", it.fallback).put("limited", it.limited))
        }
      } catch (cancelled: CancellationException) { throw cancelled
      } catch (failure: Exception) { (cached ?: throw failure).also { stale = true } }
    }
    if (bundledMusic && art.limited) return systemIcon(app, options, cacheOnly)?.copy(stale = true)
    val lightUrl = AppleIconPolicy.rendition(art.template, options, false)
    val darkUrl = AppleIconPolicy.rendition(art.template, options, true)
    val light = image(lightUrl, options, cacheOnly) ?: return null
    val dark = image(darkUrl, options, cacheOnly) ?: return null
    val distinct = distinctPixels(light.first, dark.first)
    return PairFiles(app.artworkId(options), light.first, dark.first.takeIf { distinct },
      light.second && dark.second, stale || art.limited)
  }

  private fun distinctPixels(light: File, dark: File): Boolean {
    if (light == dark) return false
    val comparisonKey = AppleIconPolicy.key("pair:${light.name}:${dark.name}")
    val comparison = File(metadata, "$comparisonKey.json")
    return readJson(comparison)?.optBoolean("distinct") ?: run {
      val dayBitmap = BitmapFactory.decodeFile(light.path) ?: throw IOException("Invalid Apple image")
      try {
        val nightBitmap = BitmapFactory.decodeFile(dark.path) ?: throw IOException("Invalid Apple image")
        try { !dayBitmap.sameAs(nightBitmap) } finally { nightBitmap.recycle() }
      } finally { dayBitmap.recycle() }
    }.also { writeJson(comparison, JSONObject().put("distinct", it)) }
  }

  private suspend fun image(url: String, options: AppleIconOptions, cacheOnly: Boolean): Pair<File, Boolean>? {
    val size = options.resolution
    // A shared CDN URL is not evidence that two storefronts supplied the same artwork.
    val index = File(metadata, "url-${AppleIconPolicy.key("${options.country}:$url")}.json")
    val previous = readJson(index)?.optString("digest")
    if (previous?.matches(Regex("[0-9a-f]{64}")) == true) {
      val existing = File(images, "$previous.png")
      if (existing.isFile && validPng(existing, size) &&
        AppleIconPolicy.digest(existing.readBytes()) == previous) {
        existing.setLastModified(System.currentTimeMillis())
        return existing to true
      }
    }
    if (cacheOnly) return null
    val bytes = client.image(url)
    val digest = AppleIconPolicy.digest(bytes)
    val file = File(images, "$digest.png")
    val temp = File.createTempFile("image-", ".tmp", images.also { it.mkdirs() })
    try {
      temp.writeBytes(bytes)
      if (!validPng(temp, size)) throw IOException("Invalid Apple PNG")
      check(temp.renameTo(file)) { "Cannot cache Apple image" }
      writeJson(index, JSONObject().put("digest", digest))
    } finally { temp.delete() }
    return file to false
  }

  private fun systemIcon(app: AppleAppMatch, options: AppleIconOptions, cacheOnly: Boolean = false): PairFiles? {
    // Apple has no App Store listing for App Store itself. Provenanced native renders ship locally.
    fun render(dark: Boolean): Pair<File, Boolean>? {
      val mode = if (dark) "dark" else "light"
      val platform = if (options.platform == ApplePlatform.MACOS) "macos" else "ios"
      val role = if (app.id == "1108187390") "music" else app.id.removePrefix("system_")
      val original = if (role == "music" && platform == "ios" && options.corners == AppleCorners.ORIGINAL) "-original" else ""
      val bytes = context.assets.open("apple-system-icons/$role-$platform$original-$mode.webp").use {
        val bytes = it.readNBytes(AppleIconPolicy.MAX_RESPONSE + 1)
        require(bytes.size <= AppleIconPolicy.MAX_RESPONSE)
        bytes
      }
      val key = AppleIconPolicy.key("system:${AppleIconPolicy.digest(bytes)}:${options.key}:$mode")
      val index = File(metadata, "local-$key.json")
      val digest = readJson(index)?.optString("digest")
      if (digest?.matches(Regex("[0-9a-f]{64}")) == true) {
        val file = File(images, "$digest.png")
        if (file.isFile && validPng(file, options.resolution) &&
          AppleIconPolicy.digest(file.readBytes()) == digest) {
          file.setLastModified(System.currentTimeMillis())
          return file to true
        }
      }
      if (cacheOnly) return null
      images.mkdirs()
      val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IOException("Missing system image")
      val file: File
      try {
        val bitmap = android.graphics.Bitmap.createScaledBitmap(source, options.resolution, options.resolution, true)
        try {
          val output = java.io.ByteArrayOutputStream()
          check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
          val data = output.toByteArray()
          val hash = AppleIconPolicy.digest(data)
          file = File(images, "$hash.png")
          val atomic = AtomicFile(file); val stream = atomic.startWrite()
          try { stream.write(data); atomic.finishWrite(stream)
          } catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
          writeJson(index, JSONObject().put("digest", hash))
        } finally { if (bitmap !== source) bitmap.recycle() }
      } finally { source.recycle() }
      return file to false
    }
    val light = render(false) ?: return null
    val dark = render(true) ?: return null
    return PairFiles(app.artworkId(options), light.first, dark.first.takeIf { distinctPixels(light.first, it) },
      light.second && dark.second)
  }

  private fun pruneImages(pinned: Set<File>) {
    val files = images.listFiles().orEmpty().filter { it.isFile }
    var size = files.sumOf { it.length() }
    for (file in files.filterNot { it in pinned }.sortedBy { it.lastModified() }) {
      if (size <= AppleIconPolicy.MAX_CACHE_BYTES) break
      val length = file.length()
      if (file.delete()) size -= length
    }
    val records = metadata.listFiles().orEmpty().filter { it.isFile }
    var bytes = records.sumOf { it.length() }
    var count = records.size
    for (file in records.sortedBy { it.lastModified() }) {
      if (bytes <= 16L * 1024 * 1024 && count <= 10_000) break
      val length = file.length()
      if (file.delete()) { bytes -= length; count-- }
    }
  }

  private fun readPublication(): JSONObject? = synchronized(publicationLock) { readJson(publication) }

  companion object {
    private fun mappingKey(pkg: String, options: AppleIconOptions) = "mapping.${options.platform.name}.$pkg"
    private fun AppleIconOptions.json() = JSONObject().put("platform", platform.name).put("resolution", resolution)
      .put("corners", corners.name).put("country", country)
    private fun AppleAppMatch.json() = JSONObject().put("id", id).put("name", name)
      .put("publisher", publisher).put("bundleId", bundleId).put("country", country)
    private fun JSONObject.match() = AppleAppMatch(getString("id"), getString("name"),
      optString("publisher"), optString("bundleId"), optString("country").takeIf { it.isNotEmpty() && it != "null" })
    private fun publishedApp(id: String, record: JSONObject): AppleAppMatch? = runCatching {
      when (record.optInt("schema", 1)) {
        1 -> AppleAppMatch(id, "", "", country = record.getJSONObject("options").getString("country"))
        2 -> AppleAppMatch(id.substringBeforeLast('_'), "", "", country = id.substringAfterLast('_'))
        else -> null
      }
    }.getOrNull()
    private fun JSONArray.matches(): List<AppleAppMatch> = (0 until length()).map { getJSONObject(it).match() }
    private fun readJson(file: File): JSONObject? = runCatching {
      val atomic = AtomicFile(file)
      if (!atomic.baseFile.exists() || atomic.baseFile.length() > 2 * 1024 * 1024) return null
      JSONObject(String(atomic.readFully(), Charsets.UTF_8))
    }.getOrNull()
    private fun writeJson(file: File, value: JSONObject) {
      file.parentFile?.mkdirs()
      val atomic = AtomicFile(file)
      val output = atomic.startWrite()
      try { output.write(value.toString().toByteArray()); atomic.finishWrite(output)
      } catch (failure: Throwable) { atomic.failWrite(output); throw failure }
    }
    internal fun validPng(file: File, size: Int): Boolean = runCatching {
      if (file.length() !in 24..AppleIconPolicy.MAX_RESPONSE.toLong()) return false
      val header = file.inputStream().use { it.readNBytes(24) }
      if (!header.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))) return false
      val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeFile(file.path, options)
      options.outWidth == size && options.outHeight == size && options.outMimeType == "image/png"
    }.getOrDefault(false)
    private fun ZipOutputStream.text(name: String, text: String) {
      putNextEntry(ZipEntry(name).apply { time = 0 })
      write(text.toByteArray())
      closeEntry()
    }
    private fun ZipOutputStream.png(name: String, file: File) {
      val crc = CRC32()
      file.inputStream().buffered().use { input ->
        val bytes = ByteArray(32 * 1024)
        while (true) { val n = input.read(bytes); if (n < 0) break; crc.update(bytes, 0, n) }
      }
      putNextEntry(ZipEntry(name).apply {
        method = ZipEntry.STORED; size = file.length(); compressedSize = size; this.crc = crc.value; time = 0
      })
      file.inputStream().buffered().use { it.copyTo(this) }
      closeEntry()
    }
  }
}
