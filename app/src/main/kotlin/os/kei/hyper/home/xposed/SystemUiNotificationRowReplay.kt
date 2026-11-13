package os.kei.hyper.home.xposed

import android.service.notification.StatusBarNotification
import android.os.UserHandle
import android.view.View
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal class SystemUiNotificationRowReplay(
  private val rowType: Class<out View>,
  private val rebind: Method,
  private val currentUser: Int,
  private val ready: () -> Boolean,
  private val now: () -> Long = System::currentTimeMillis,
) {
  private class Row(view: View, var postTime: Long) {
    val view = WeakReference(view)
    var consumed = false
  }

  private val lock = Any()
  private val startedAt = now()
  private val rows = ArrayList<Row>(8)
  @Volatile private var stopped = false

  init {
    require(currentUser >= 0)
    require(rebind.parameterCount == 0 && rebind.returnType == Void.TYPE &&
      !Modifier.isStatic(rebind.modifiers) && rebind.declaringClass.isAssignableFrom(rowType))
  }

  fun remember(authenticatedImageView: View, notification: StatusBarNotification): Boolean = synchronized(lock) {
    if (stopped || !withinWindow()) return@synchronized false
    val view = rowFor(authenticatedImageView) ?: return@synchronized false
    rows.removeAll { it.view.get() == null }
    val existing = rows.firstOrNull { it.view.get() === view }
    if (notification.packageName != "android" || notification.uid < 0 ||
      notification.uid % 100000 != 1000 || notification.uid / 100000 != currentUser ||
      notification.user != UserHandle.getUserHandleForUid(notification.uid) || !fresh(notification.postTime)) {
      existing?.consumed = true
      return@synchronized false
    }
    if (existing != null) {
      if (!existing.consumed) existing.postTime = notification.postTime
      return@synchronized false
    }
    if (rows.size == 8) return@synchronized false
    rows.add(Row(view, notification.postTime))
    true
  }

  fun replay(): Int {
    val snapshot = synchronized(lock) {
      if (stopped) return 0
      if (!withinWindow()) { rows.clear(); return 0 }
      rows.toList()
    }
    if (!ready()) return 0
    var count = 0
    for (entry in snapshot) {
      val row = synchronized(lock) {
        if (stopped) return count
        if (!withinWindow()) { rows.clear(); return count }
        if (entry.consumed) return@synchronized null
        val view = entry.view.get() ?: return@synchronized null
        if (!fresh(entry.postTime)) {
          entry.consumed = true
          return@synchronized null
        }
        if (!view.isAttachedToWindow) return@synchronized null
        entry.consumed = true
        view
      } ?: continue
      try {
        rebind.invoke(row)
      } catch (error: InvocationTargetException) {
        throw error.targetException
      }
      count++
    }
    return count
  }

  fun clear() = synchronized(lock) {
    stopped = true
    rows.clear()
  }

  private fun fresh(postTime: Long): Boolean = now() - postTime in -1000L..30000L
  private fun withinWindow(): Boolean = now() - startedAt in 0L..30000L

  private fun rowFor(view: View): View? {
    var parent = view.parent
    repeat(12) {
      val candidate = parent as? View ?: return null
      if (rowType.isInstance(candidate)) return candidate
      parent = candidate.parent
    }
    return null
  }
}
