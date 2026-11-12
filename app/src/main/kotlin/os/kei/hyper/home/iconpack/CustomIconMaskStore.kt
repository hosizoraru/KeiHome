package os.kei.hyper.home.iconpack

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.provider.OpenableColumns
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class CustomIconMask(val pathData: String, val name: String, val digest: String)

internal class CustomIconMaskStore(
  context: Context,
  private val worker: CoroutineDispatcher = Dispatchers.IO,
) {
  private val application = context.applicationContext ?: context
  private val file = File(application.filesDir, "icon-shape/custom-mask.json")
  private val atomic = AtomicFile(file)
  private val state = states.getOrPut(file.absolutePath) { State() }

  suspend fun read(): CustomIconMask? = withContext(worker) { readNow() }

  /** Worker-thread only: performs bounded disk IO and Android path validation synchronously.
   * Snapshot once per renderer/session/handoff; never call from composition or the main thread.
   */
  internal fun readNow(): CustomIconMask? {
    check(Looper.myLooper() != Looper.getMainLooper()) { "Custom mask reads require a worker thread" }
    return synchronized(state) {
      try {
        safeFiles()
        val bytes = raw() ?: return@synchronized null
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        require(json.getInt("version") == 1)
        val path = json.getString("pathData")
        require(path == NativeIconMaskImport.canonicalPath(path))
        val name = json.getString("name")
        require(name == displayName(name))
        val digest = digest(path)
        require(json.getString("digest") == digest)
        NativeAdaptiveIconMask.fromPathData(file.path, path)
        CustomIconMask(path, name, digest)
      } catch (cancelled: CancellationException) { throw cancelled
      } catch (_: Exception) { null }
    }
  }

  suspend fun import(uri: Uri): CustomIconMask {
    val mask = withContext(worker) {
      require(uri.scheme == "content") { "Select mask XML using the system document picker" }
      val caller = currentCoroutineContext()
      val resolver = application.contentResolver
      val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }
          ?.let(cursor::getString) else null
      }
      caller.ensureActive()
      val bytes = (resolver.openInputStream(uri) ?: throw IOException("Mask XML cannot be opened")).use {
        bounded(it, NativeIconMaskImport.MAX_XML_BYTES) { caller.ensureActive() }
      }
      val path = NativeIconMaskImport.pathData(bytes)
      caller.ensureActive()
      NativeAdaptiveIconMask.fromPathData("custom-mask", path)
      CustomIconMask(path, displayName(name), digest(path))
    }
    val bytes = JSONObject().put("version", 1).put("pathData", mask.pathData)
      .put("name", mask.name).put("digest", mask.digest).toString().toByteArray(Charsets.UTF_8)
    require(bytes.size <= MAX_RECORD_BYTES)
    replace(bytes)
    return mask
  }

  suspend fun reset() = replace(null)

  private suspend fun replace(bytes: ByteArray?) {
    var committed: Commit? = null
    try {
      withContext(worker) {
        val caller = currentCoroutineContext()
        synchronized(state) {
          caller.ensureActive()
          safeFiles()
          // An oversized record cannot be one of our valid masks; allow import/reset to repair it.
          val before = try { raw() } catch (_: SizeLimitException) { null }
          try {
            publish(bytes) { caller.ensureActive() }
          } catch (failure: Throwable) {
            try { if (!same(raw(), before)) publish(before) } catch (restore: Throwable) { failure.addSuppressed(restore) }
            throw failure
          }
          committed = Commit(++state.generation, before)
          caller.ensureActive()
        }
      }
    } catch (cancelled: CancellationException) {
      // withContext can cancel delivery after a successful IO commit. Restore only if no later
      // import/reset has superseded this transaction.
      committed?.let { commit ->
        withContext(NonCancellable + worker) {
          synchronized(state) {
            if (state.generation == commit.generation) {
              try { publish(commit.before); state.generation++ }
              catch (restore: Throwable) { cancelled.addSuppressed(restore) }
            }
          }
        }
      }
      throw cancelled
    }
  }

  private fun publish(bytes: ByteArray?, check: () -> Unit = {}) {
    if (bytes == null) {
      check()
      atomic.delete()
      if (listOf(file, File("${file.path}.new"), File("${file.path}.bak")).any { it.exists() }) {
        throw IOException("Custom mask could not be reset")
      }
      return
    }
    val stream = atomic.startWrite()
    try {
      stream.write(bytes)
      stream.fd.sync()
      check()
      atomic.finishWrite(stream)
    } catch (failure: Throwable) {
      atomic.failWrite(stream)
      throw failure
    }
    if (!same(raw(), bytes)) throw IOException("Custom mask was not committed")
  }

  private fun raw(): ByteArray? = try {
    atomic.openRead().use { bounded(it, MAX_RECORD_BYTES) }
  } catch (_: FileNotFoundException) { null }

  private fun safeFiles() {
    val directory = requireNotNull(file.parentFile)
    if (Files.isSymbolicLink(directory.toPath()) ||
      directory.exists() && !directory.isDirectory) throw IOException("Unsafe custom mask directory")
    for (candidate in listOf(file, File("${file.path}.new"), File("${file.path}.bak"))) {
      if (Files.isSymbolicLink(candidate.toPath()) || candidate.exists() && !candidate.isFile) {
        throw IOException("Unsafe custom mask record")
      }
    }
  }

  private class State(var generation: Long = 0)
  private data class Commit(val generation: Long, val before: ByteArray?)
  private class SizeLimitException : IOException("Custom mask size limit exceeded")

  companion object {
    private const val MAX_RECORD_BYTES = 16 * 1024
    private val states = ConcurrentHashMap<String, State>()

    private fun same(first: ByteArray?, second: ByteArray?): Boolean =
      if (first == null || second == null) first == null && second == null else first.contentEquals(second)

    private fun bounded(input: InputStream, limit: Int, check: () -> Unit = {}): ByteArray {
      val output = ByteArrayOutputStream()
      val buffer = ByteArray(1024)
      while (true) {
        check()
        val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
        if (count < 0) break
        if (count == 0) {
          val byte = input.read()
          if (byte < 0) break
          output.write(byte)
        } else output.write(buffer, 0, count)
        if (output.size() > limit) throw SizeLimitException()
      }
      return output.toByteArray()
    }

    private fun displayName(value: String?): String {
      val normalized = buildString {
        val text = Normalizer.normalize(value.orEmpty().take(1024), Normalizer.Form.NFKC)
        var offset = 0
        while (offset < text.length) {
          val codePoint = text.codePointAt(offset)
          val type = Character.getType(codePoint)
          if (codePoint == '/'.code || codePoint == '\\'.code || Character.isISOControl(codePoint) ||
            Character.isWhitespace(codePoint) || type == Character.FORMAT.toInt() ||
            type == Character.SURROGATE.toInt()) append(' ') else appendCodePoint(codePoint)
          offset += Character.charCount(codePoint)
        }
      }.replace(Regex(" +"), " ").trim()
      if (normalized.isEmpty()) return "transform_config.xml"
      return normalized.substring(0, normalized.offsetByCodePoints(0, minOf(128, normalized.codePointCount(0, normalized.length))))
    }

    private fun digest(path: String): String = MessageDigest.getInstance("SHA-256")
      .digest(path.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
  }
}
