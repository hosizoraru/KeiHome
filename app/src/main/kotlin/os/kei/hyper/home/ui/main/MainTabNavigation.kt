package os.kei.hyper.home.ui.main

import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.utils.springAnimateToPage

/** A tab click owns selection until its scroll completes or a user gesture interrupts it. */
@Stable
internal class MainTabNavigation(
  private val pager: PagerState,
  private val scope: CoroutineScope,
) {
  private var destination by mutableStateOf<Int?>(null)
  private var navigationJob: Job? = null
  private var generation = 0L

  val selectedPage: Int get() = destination ?: pager.currentPage

  fun selectPage(index: Int, animate: Boolean = true, onArrived: () -> Unit = {}) {
    if (index !in 0 until pager.pageCount) return
    if (animate && index == selectedPage &&
      (navigationJob?.isActive == true ||
        (pager.currentPage == index && abs(pager.currentPageOffsetFraction) <= 0.0001f))) return
    val request = ++generation
    navigationJob?.cancel()
    destination = index
    navigationJob = scope.launch {
      try {
        if (animate) pager.springAnimateToPage(index)
        pager.scrollToPage(index)
        onArrived()
      } finally {
        // A cancelled predecessor must never release the newer click's selection.
        if (request == generation) destination = null
      }
    }
  }
}
