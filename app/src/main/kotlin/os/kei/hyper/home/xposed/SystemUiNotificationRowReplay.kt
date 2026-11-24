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
) {
  private class Row(view: View) {
    val view = WeakReference(view)
    var pending = true
    var rebinding = false
  }

  private val lock = Any()
  private val rows = ArrayList<Row>(8)
  @Volatile private var stopped = false

  init {
    require(currentUser >= 0)
    require(rebind.parameterCount == 0 && rebind.returnType == Void.TYPE &&
      !Modifier.isStatic(rebind.modifiers) && rebind.declaringClass.isAssignableFrom(rowType))
  }

  fun remember(authenticatedImageView: View, notification: StatusBarNotification): Boolean = synchronized(lock) {
    if (stopped) return@synchronized false
    val view = rowFor(authenticatedImageView) ?: return@synchronized false
    rows.removeAll { it.view.get() == null }
    val existing = rows.firstOrNull { it.view.get() === view }
    if (notification.uid < 0 || notification.uid / 100000 != currentUser ||
      notification.user != UserHandle.getUserHandleForUid(notification.uid)) {
      rows.removeAll { it === existing }
      return@synchronized false
    }
    if (existing != null) {
      if (existing.rebinding) return@synchronized false
      return@synchronized false
    }
    if (rows.size == 8) return@synchronized false
    rows.add(Row(view))
    true
  }

  fun replay(): Int {
    val snapshot = synchronized(lock) {
      if (stopped) return 0
      rows.toList()
    }
    if (!ready()) return 0
    var count = 0
    for (entry in snapshot) {
      val row = synchronized(lock) {
        if (stopped) return count
        if (!entry.pending || entry.rebinding) return@synchronized null
        val view = entry.view.get() ?: return@synchronized null
        if (!view.isAttachedToWindow) return@synchronized null
        entry.pending = false
        entry.rebinding = true
        view
      } ?: continue
      try {
        rebind.invoke(row)
      } catch (error: InvocationTargetException) {
        synchronized(lock) {
          entry.rebinding = false
        }
        throw error.targetException
      }
      synchronized(lock) { entry.rebinding = false }
      count++
    }
    return count
  }

  fun markPending() = synchronized(lock) {
    if (stopped) return@synchronized
    rows.removeAll { it.view.get() == null }
    rows.forEach { if (!it.rebinding) it.pending = true }
  }

  fun clear() = synchronized(lock) {
    stopped = true
    rows.clear()
  }

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
