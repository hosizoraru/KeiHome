package os.kei.hyper.home.status

import android.content.Context
import android.os.Bundle
import os.kei.hyper.home.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class NavigationBackdropStatus(val version: Long, val available: Boolean, val choice: String)

internal object NavigationBackdropInbox {
  private val latest = MutableStateFlow<NavigationBackdropStatus?>(null)
  val status = latest.asStateFlow()
  const val METHOD = "navigation_backdrop_status"
  private const val FILE = "navigation_backdrop_runtime_v1"

  @Synchronized fun read(context: Context): NavigationBackdropStatus? {
    val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    if (!preferences.contains("version")) { latest.value = null; return null }
    return NavigationBackdropStatus(preferences.getLong("version", 0), preferences.getBoolean("available", false),
      preferences.getString("choice", "").orEmpty())
      .also { latest.value = it }
  }

  fun publish(context: Context, available: Boolean, choice: String) {
    val receipt = context.contentResolver.call(RuntimeStatusInbox.AUTHORITY, METHOD, null, Bundle().apply {
      putLong("version", BuildConfig.VERSION_CODE.toLong())
      putBoolean("available", available)
      putString("choice", choice)
    })
    check(receipt?.getBoolean("accepted", false) == true)
  }

  @Synchronized fun accept(context: Context, extras: Bundle): Boolean {
    val version = extras.getLong("version", 0)
    if (version != BuildConfig.VERSION_CODE.toLong()) return false
    val choice = extras.getString("choice").orEmpty()
    if (!choice.matches(Regex("[0-9a-f]{64}"))) return false
    check(context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
      .putLong("version", version).putBoolean("available", extras.getBoolean("available", false))
      .putString("choice", choice).commit())
    latest.value = NavigationBackdropStatus(version, extras.getBoolean("available", false), choice)
    return true
  }
}
