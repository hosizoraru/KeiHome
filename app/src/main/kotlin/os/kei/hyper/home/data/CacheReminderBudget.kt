package os.kei.hyper.home.data

/** Decimal byte thresholds for reminders only; they do not limit downloads or delete resources. */
internal object CacheReminderBudget {
  const val DEFAULT_BYTES: Long = 520_000_000L
  const val MIN_BYTES: Long = 50_000_000L
  const val MAX_BYTES: Long = 20_000_000_000L

  fun isValidCustom(bytes: Long): Boolean = bytes in MIN_BYTES..MAX_BYTES

  fun resolve(customBytes: Long): Long = when {
    isValidCustom(customBytes) -> customBytes
    else -> DEFAULT_BYTES
  }
}
