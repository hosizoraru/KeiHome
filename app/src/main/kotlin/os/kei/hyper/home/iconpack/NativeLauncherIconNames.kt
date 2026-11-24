package os.kei.hyper.home.iconpack

/** HyperOS uses the component's namespace, rather than global class-name uniqueness. */
internal fun nativeLauncherActivityName(target: IconPackComponent): String? {
  val activity = target.className ?: return null
  val fullName = if (activity.startsWith('.')) target.packageName + activity else activity
  return if (fullName.startsWith(target.packageName) && TelegramChannelIcons.foreignLauncherClass(target) == null)
    fullName else "${target.packageName}#$fullName"
}

internal fun nativeLauncherIconNames(target: IconPackComponent): List<String> {
  val legacy = target.className?.let { if (it.startsWith('.')) target.packageName + it else it }
  return listOfNotNull(nativeLauncherActivityName(target),
    legacy?.takeUnless { it == TelegramChannelIcons.foreignLauncherClass(target) }, target.packageName).distinct()
}

/** Qualified native stems are filenames, never Java component names or archive paths. */
internal fun validNativeIconName(value: String): Boolean {
  val separator = value.indexOf('#')
  if (separator < 0) return validDottedName(value)
  return value.indexOf('#', separator + 1) < 0 &&
    validDottedName(value.substring(0, separator)) && validDottedName(value.substring(separator + 1))
}
