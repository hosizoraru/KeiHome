package os.kei.hyper.home.xposed

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import java.util.concurrent.CancellationException

internal fun islandAppIconPackage(
  requestedName: String?, requestedUser: Int?, infoName: String?, appUid: Int, processUser: Int,
): String? {
  if (requestedUser == null || requestedName != infoName) return null
  return notificationAppIconPackage(infoName, appUid, requestedUser, processUser)
}

/**
 * Only called from the proven plugin app-identity factory, never from picture/media loaders.
 * Native misses remain native; the exact returned Drawable can bind to the shared late cache.
 */
internal class SystemUiIslandAppIconAdapter(
  private val processUser: Int,
  private val lifecycle: ThemeCompatibilityLifecycle,
  private val snapshot: (String) -> Bitmap?,
  private val remember: (Drawable, String) -> Unit,
  onFailure: (Throwable) -> Unit,
) {
  private val publication = Any()
  @Volatile private var stopped = false
  private val guard = SystemUiHookGuard(onFailure)

  fun intercept(
    requestedName: String?,
    requestedUser: Int?,
    info: ApplicationInfo?,
    iconContext: Context?,
    native: () -> Any?,
  ): Any? {
    if (stopped || !lifecycle.enter()) return native()
    try {
      val name = guard.run<String?>(null) {
        if (stopped || iconContext == null || info == null) null else
          islandAppIconPackage(requestedName, requestedUser, info.packageName, info.uid, processUser)
      }
      if (name != null) {
        var cancelled = false
        val replacement = guard.run<Drawable?>(null) {
          val bitmap = try { snapshot(name) } catch (_: CancellationException) {
            cancelled = true
            null
          }?.takeUnless { it.isRecycled } ?: return@run null
          if (stopped) return@run null
          val drawable = BitmapDrawable(requireNotNull(iconContext).resources, bitmap)
          synchronized(publication) {
            if (stopped) null else drawable.also { remember(it, name) }
          }
        }
        // Cancelling our lookup must not cancel the SystemUI caller's native bind.
        if (cancelled) return native()
        if (replacement != null) return replacement
      }
      // Native errors must propagate, not be caught and followed by a second native invocation.
      val result = native()
      if (name != null && result is Drawable) guard.run(Unit) {
        synchronized(publication) {
          if (!stopped) remember(result, name)
        }
      }
      return result
    } finally { lifecycle.leave() }
  }

  /** No workers or native bitmap ownership; fence publication before the shared bindings clear. */
  fun stop() = synchronized(publication) { stopped = true }
}
