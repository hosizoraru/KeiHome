package os.kei.hyper.home.iconpack.perfect

import android.content.Context
import kotlinx.coroutines.runBlocking

/** Called only by the app's existing off-main-thread preview/export workers. */
internal object PerfectSystemFallback {
  private var repository: PerfectIconRepository? = null

  @Synchronized
  private fun repository(context: Context): PerfectIconRepository =
    repository ?: PerfectIconRepository(context.applicationContext).also { repository = it }

  fun sourceHash(context: Context, style: String): String =
    runBlocking { repository(context).fallbackSourceHash(style) }

  fun icon(context: Context, style: String, names: List<String>, sourceHash: String): PerfectIconRepository.FallbackIcon? =
    runBlocking { repository(context).fallbackIcon(style, names, sourceHash) }
}
