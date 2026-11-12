package os.kei.hyper.home.iconpack.perfect

import android.content.Context
import java.io.Closeable
import kotlinx.coroutines.runBlocking

/** Called only by the app's existing off-main-thread preview/export workers. */
internal object PerfectSystemFallback {
  private var repository: PerfectIconRepository? = null

  @Synchronized
  private fun repository(context: Context): PerfectIconRepository =
    repository ?: PerfectIconRepository(context.applicationContext).also { repository = it }

  internal class Source internal constructor(private val session: PerfectIconRepository.FallbackLookupSession) : Closeable {
    val sourceHash: String get() = session.sourceHash
    val nativeNames: Set<String> get() = session.nativeNames
    fun icon(names: List<String>): PerfectIconRepository.FallbackIcon? = runBlocking { session.icon(names) }
    fun validate() = runBlocking { session.validate() }
    override fun close() = runBlocking { session.close() }
  }

  /** Pin one downloaded style for an off-main-thread preview/export batch; throws if unavailable. */
  fun open(context: Context, style: String): Source =
    runBlocking { Source(repository(context).openFallbackSession(style)) }

  fun sourceHash(context: Context, style: String): String =
    runBlocking { repository(context).fallbackSourceHash(style) }

  fun icon(context: Context, style: String, names: List<String>, sourceHash: String): PerfectIconRepository.FallbackIcon? =
    runBlocking { repository(context).fallbackIcon(style, names, sourceHash) }
}
