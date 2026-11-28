package os.kei.hyper.home.xposed

import android.content.pm.ApplicationInfo
import android.graphics.drawable.Icon
import android.graphics.drawable.Drawable
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.widget.ImageView
import java.lang.ref.WeakReference
import os.kei.hyper.home.iconpack.validDottedName

/** Only the framework appInfo of the authenticated header consumer can fill a missing frame. */
internal object SystemUiAppIconIdentity {
  fun name(info: ApplicationInfo?, currentUser: Int, customOwner: String?): String? {
    if (info == null || currentUser < 0 || info.uid < 1000 ||
      info.uid / 100000 != currentUser || info.uid % 100000 < 1000) return null
    val name = info.packageName ?: return null
    if (name.length > 240 || !validDottedName(name)) return null
    if (!customOwner.isNullOrEmpty() && customOwner != name) return null
    return name
  }
}

/** Identity-only provenance; never retain notification views, Icons, or native async work. */
internal class SystemUiAppIconBindings {
  private class Token(icon: Any, val name: String) {
    val icon = WeakReference(icon)
  }
  class Binding(
    view: ImageView,
    val name: String,
    val original: WeakReference<Drawable>? = null,
    var usedSnapshot: Boolean = false,
  ) {
    val view = WeakReference(view)
  }
  private val tokens = ArrayList<Token>()
  private val views = ArrayList<Binding>()

  @Synchronized fun remember(icon: Any, name: String) {
    require(icon is Icon || icon is Drawable || icon is Bitmap)
    tokens.removeAll { it.icon.get() == null || it.icon.get() === icon }
    if (tokens.size >= 96) tokens.removeAt(0)
    tokens += Token(icon, name)
  }

  @Synchronized fun rememberLoaded(icon: Icon, drawable: Drawable) {
    val name = tokens.firstOrNull { it.icon.get() === icon }?.name ?: return
    remember(drawable, name)
  }

  @Synchronized fun rememberCreated(bitmap: Bitmap, icon: Icon) {
    val name = tokens.firstOrNull { it.icon.get() === bitmap }?.name ?: return
    remember(icon, name)
  }

  @Synchronized fun bindNative(
    view: ImageView,
    cached: Drawable,
    name: String,
    original: Drawable? = null,
  ): Binding? {
    if (view.drawable !== cached) {
      clear(view)
      return null
    }
    remember(cached, name)
    return bind(view, cached, original)
  }

  @Synchronized fun associated(view: ImageView, name: String): Boolean =
    views.any { it.view.get() === view && it.name == name }

  @Synchronized fun bind(view: ImageView, icon: Any?, original: Drawable? = null): Binding? {
    views.removeAll { it.view.get() == null || it.view.get() === view }
    val bitmap = (icon as? BitmapDrawable)?.bitmap
    val name = tokens.firstOrNull {
      val known = it.icon.get()
      known != null && (known === icon || bitmap != null && known === bitmap)
    }?.name ?: return null
    if (views.size >= 64) {
      val detached = views.indexOfFirst { it.view.get()?.isAttachedToWindow == false }
      views.removeAt(if (detached >= 0) detached else 0)
    }
    return Binding(view, name, original?.let(::WeakReference)).also { views += it }
  }

  @Synchronized fun clear(view: ImageView) {
    views.removeAll { it.view.get() == null || it.view.get() === view }
  }

  @Synchronized fun current(binding: Binding): ImageView? =
    if (views.any { it === binding }) binding.view.get() else null

  @Synchronized fun snapshot(): List<Binding> {
    views.removeAll { it.view.get() == null }
    return views.toList()
  }

  /** A recycled/rebound slot must not receive a stale native async result either. */
  fun afterNative(binding: Binding, native: Runnable, ready: (Binding) -> Unit): Runnable = Runnable {
    if (current(binding) != null) {
      native.run()
      if (current(binding) != null) ready(binding)
    }
  }

  @Synchronized fun clear() {
    tokens.clear()
    views.clear()
  }
}
