package os.kei.hyper.home.xposed

import android.graphics.drawable.Icon
import android.view.View
import java.lang.ref.WeakReference
import os.kei.hyper.home.iconpack.validDottedName

/** Loader-neutral data only; a retired module must never remain reachable from the view tag. */
internal object SystemUiStatusBarIconProof {
  const val KEY = 0x7e4b5342
  private const val MARKER = "os.kei.hyper.home.statusbar.app-proof.v1"
  private val arrayType = arrayOfNulls<Any>(0).javaClass

  private fun owned(value: Any?): Array<*>? {
    if (value == null || value.javaClass !== arrayType) return null
    val data = value as Array<*>
    if (data.size != 4 || (data[0] as? String) != MARKER) return null
    val name = data[1] as? String ?: return null
    val user = data[2] as? Int ?: return null
    if (name.length > 240 || !validDottedName(name) || user < 0) return null
    val weak = data[3] ?: return null
    if (weak.javaClass !== WeakReference::class.java ||
      (weak as WeakReference<*>).get()?.let { it !is Icon } == true) return null
    return data
  }

  fun write(view: View, icon: Icon, user: Int, name: String): Boolean {
    if (icon.type != Icon.TYPE_BITMAP || user < 0 || name.length > 240 || !validDottedName(name)) return false
    val previous = view.getTag(KEY)
    if (previous != null && owned(previous) == null) return false
    view.setTag(KEY, arrayOf<Any>(MARKER, name, user, WeakReference(icon)))
    return true
  }

  fun read(view: View, icon: Icon, user: Int): String? {
    if (icon.type != Icon.TYPE_BITMAP || user < 0) return null
    val data = owned(view.getTag(KEY)) ?: return null
    if (data[2] != user || (data[3] as WeakReference<*>).get() !== icon) return null
    return data[1] as String
  }
}
