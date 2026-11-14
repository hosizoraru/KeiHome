package os.kei.hyper.home.ui.main

import android.os.Trace
import java.util.concurrent.atomic.AtomicInteger

private val iconReadTraceCookie = AtomicInteger()

/** Async slices remain paired when a suspended read resumes on a different worker thread. */
internal suspend fun <T> traceIconInformation(stage: String, read: suspend () -> T): T {
  if (!Trace.isEnabled()) return read()
  val cookie = iconReadTraceCookie.incrementAndGet()
  val name = "KeiHome:icon-info:$stage"
  Trace.beginAsyncSection(name, cookie)
  try { return read() } finally { Trace.endAsyncSection(name, cookie) }
}
