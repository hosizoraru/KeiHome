package os.kei.hyper.home.xposed

import os.kei.hyper.home.data.BILIBILI_PACKAGE

internal fun ownsReloadableJava(process: String, uid: Int): Boolean =
  when (process.substringBefore(':')) {
    "system", "system_server" -> uid == android.os.Process.SYSTEM_UID && ':' !in process
    HyperOsShareProfile.PACKAGE, SystemUiThemeCompatibilityRuntime.PACKAGE_NAME,
    ThemeIconImportRuntime.PACKAGE_NAME -> true
    else -> HostAppIconRuntime.supports(process) || process == BILIBILI_PACKAGE
  }

/** Quiet app hosts rarely allocate enough in background to collect retired module loaders on their own. */
internal fun supportsRetiredCollectionHint(process: String): Boolean =
  process.isNotEmpty() && process != "system" && process != "system_server"
