package os.kei.hyper.home.data

internal enum class SystemIconFallback {
  DEFAULT, CLASSIC, BOUNDLESS;

  fun sourceId(id: String): String = "${baseSourceId(id)}$SOURCE_SUFFIX$name"

  companion object {
    private const val SOURCE_SUFFIX = ":system-fallback-v1:"
    fun baseSourceId(id: String): String = id.substringBefore(SOURCE_SUFFIX)
  }
}
