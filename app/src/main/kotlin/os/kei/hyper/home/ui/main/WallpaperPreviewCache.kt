package os.kei.hyper.home.ui.main

import android.app.WallpaperManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import os.kei.hyper.home.status.WallpaperPreviewProtocol.Preview
import os.kei.hyper.home.status.WallpaperPreviewProtocol.ReadResult

/** The two decoded thumbnails are shared by the desktop and settings-search entry points. */
internal class WallpaperPreviewCache(
  private val read: suspend (screen: Int, cached: Preview?, force: Boolean) -> ReadResult?,
) {
  data class Snapshot(
    val previews: Map<Int, Preview> = emptyMap(),
    val failed: Set<Int> = emptySet(),
    val refreshing: Boolean = false,
    val separate: Boolean? = null,
  )

  private val mutableState = MutableStateFlow(Snapshot())
  val state = mutableState.asStateFlow()
  private val refreshLock = Mutex()

  suspend fun refresh(first: Int, force: Boolean = false) = refreshLock.withLock {
    val home = WallpaperManager.FLAG_SYSTEM
    val lock = WallpaperManager.FLAG_LOCK
    mutableState.value = mutableState.value.copy(refreshing = true, failed = emptySet())
    try {
      val priority = if (mutableState.value.separate == false) home else first
      for (screen in listOf(priority, if (priority == home) lock else home)) {
        val cached = mutableState.value.previews[screen]
        val result = read(screen, cached, force)
        val preview = result?.preview
        val now = mutableState.value
        val current = now.copy(separate = result?.separateDimming ?: now.separate)
        mutableState.value = if (preview == null) current.copy(failed = current.failed + screen)
        else current.copy(previews = current.previews + (screen to preview))
        if (screen == home && mutableState.value.separate == false) break
      }
    } finally {
      mutableState.value = mutableState.value.copy(refreshing = false)
    }
  }
}
